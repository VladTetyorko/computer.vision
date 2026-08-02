package com.drones.vision.adapter.mjpeg;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * One viewer's decode → encode → pace loop: opens its own {@link FFmpegFrameGrabber} against a
 * feed's source file and writes {@code multipart/x-mixed-replace} JPEG parts to an HTTP response
 * body at the source's own native frame rate, until end-of-stream (when not looping) or the
 * caller-supplied stop signal reports true. Extracted from {@code MjpegFeedTransmitter} (see its
 * MODULE.md) so this concern is separable from HTTP plumbing/per-feed bookkeeping ({@code
 * MjpegFeedTransmitter.FeedRegistration}) and shared-server lifecycle ({@link
 * MjpegHttpServerHost}).
 *
 * <p><b>Real-time pacing</b> duplicates the timestamp-delta approach of {@code adapter-rtsp}'s
 * {@code FfmpegVideoSource}/{@code RtspFeedTransmitter} (deliberately not shared — adapters must
 * not depend on each other, per {@code CLAUDE.md}).
 *
 * <p>Package-private: {@code MjpegFeedTransmitter} is this class's only caller. Not
 * reusable/thread-safe — one instance streams exactly one viewer's connection once, exactly like
 * {@code MjpegVideoSource}'s per-open runtime is never shared across opens.
 */
final class MjpegViewerSession {

    private static final String BOUNDARY_TOKEN = "visionmjpegboundary";

    /** The full {@code Content-Type} header value viewers of this module's TX side receive. */
    static final String CONTENT_TYPE = "multipart/x-mixed-replace; boundary=" + BOUNDARY_TOKEN;

    private final Path sourcePath;
    private final boolean loop;
    private final float jpegQuality;

    MjpegViewerSession(Path sourcePath, boolean loop, float jpegQuality) {
        this.sourcePath = sourcePath;
        this.loop = loop;
        this.jpegQuality = jpegQuality;
    }

    // -- native log quieting --------------------------------------------------
    // Duplicated (not shared) from adapter-rtsp's FfmpegVideoSource/RtspFeedTransmitter and
    // adapter-publish-hls's MediamtxStreamPublisher: per CLAUDE.md's dependency rule, adapters
    // must not depend on each other, so this small guard is intentionally copy-pasted rather
    // than factored into a shared utility module. See FfmpegVideoSource's MODULE.md/javadoc for
    // the empirical reasoning behind plain av_log_set_level over FFmpegLogCallback.set().
    private static boolean quietLoggingConfigured = false;

    /**
     * Idempotent; lowers FFmpeg's native log threshold to {@code AV_LOG_ERROR} so grabber
     * start/stop no longer dumps INFO-level banners to stdout/stderr. Safe to call repeatedly.
     */
    static synchronized void ensureQuietLogging() {
        if (quietLoggingConfigured) {
            return;
        }
        // ERROR, not WARNING: swscale's per-frame "deprecated pixel format used"
        // warning on yuvj-tagged inputs is benign, unavoidable via JavaCV's
        // high-level API, and drowns real logs. See FfmpegVideoSource (adapter-rtsp).
        avutil.av_log_set_level(avutil.AV_LOG_ERROR);
        quietLoggingConfigured = true;
    }

    /**
     * Runs the grab/decode/pace/encode loop, writing multipart JPEG parts to {@code out}.
     * Returns (does not throw) on a graceful end of stream when not looping; an I/O failure
     * writing to {@code out} (e.g. the viewer disconnected) propagates.
     *
     * @param out           the HTTP response body to write multipart parts to
     * @param stopRequested polled once per grabbed frame/restart; once true the loop exits
     */
    void stream(OutputStream out, BooleanSupplier stopRequested) throws IOException {
        Java2DFrameConverter converter = new Java2DFrameConverter();
        FFmpegFrameGrabber grabber = null;
        try {
            grabber = new FFmpegFrameGrabber(sourcePath.toString());
            grabber.start();

            // Real-time pacing (duplicated from FfmpegVideoSource/RtspFeedTransmitter, see class
            // javadoc): -1 means "no previous frame yet" -- the next grabbed frame sets the
            // baseline without sleeping, whether the very first frame or after a restart.
            long pacingBaselineTimestampMicros = -1;
            long pacingBaselineWallNanos = 0;
            while (!stopRequested.getAsBoolean()) {
                // grabImage(), not grab(): audio frames' look-ahead timestamps would stall the
                // pacing sleep (see adapter-rtsp FfmpegVideoSource's grab loop).
                Frame frame = grabber.grabImage();
                if (frame == null) {
                    if (loop && !stopRequested.getAsBoolean()) {
                        grabber.restart(); // stop() + start(): reopens the file from the beginning
                        pacingBaselineTimestampMicros = -1;
                        continue;
                    }
                    break; // end of stream and not looping -- graceful completion
                }
                long timestampMicros = grabber.getTimestamp();
                if (pacingBaselineTimestampMicros >= 0) {
                    long targetDeltaMicros = timestampMicros - pacingBaselineTimestampMicros;
                    long elapsedMicros = (System.nanoTime() - pacingBaselineWallNanos) / 1_000L;
                    sleepMicros(targetDeltaMicros - elapsedMicros);
                }
                pacingBaselineTimestampMicros = timestampMicros;
                pacingBaselineWallNanos = System.nanoTime();

                if (frame.image == null || frame.image.length == 0) {
                    continue; // audio/data-only frame: nothing to encode
                }
                BufferedImage image = converter.convert(frame);
                if (image == null) {
                    continue;
                }
                writePart(out, encodeJpeg(image));
            }
        } finally {
            releaseQuietly(grabber);
        }
    }

    private static void writePart(OutputStream out, byte[] jpeg) throws IOException {
        String header = "--" + BOUNDARY_TOKEN + "\r\n"
                + "Content-Type: image/jpeg\r\n"
                + "Content-Length: " + jpeg.length + "\r\n"
                + "\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(jpeg);
        out.write('\r');
        out.write('\n');
        out.flush();
    }

    private byte[] encodeJpeg(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG writer available for MJPEG feed encoding");
        }
        ImageWriter writer = writers.next();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ImageOutputStream imageOut = ImageIO.createImageOutputStream(bytes)) {
                writer.setOutput(imageOut);
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(jpegQuality);
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static void releaseQuietly(FFmpegFrameGrabber grabber) {
        if (grabber == null) {
            return;
        }
        try {
            grabber.release();
        } catch (Exception ignored) {
            // best-effort cleanup; nothing more actionable if release fails
        }
    }

    /** Sleeps the given microsecond duration; clamps negative/zero to a no-op. */
    private static void sleepMicros(long micros) {
        if (micros <= 0) {
            return;
        }
        try {
            TimeUnit.MICROSECONDS.sleep(micros);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

package com.drones.vision.adapter.publishhls;

import com.drones.vision.domain.model.VideoFrame;

import org.bytedeco.javacv.Frame;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;

/**
 * Converts between a domain {@link VideoFrame} and a JavaCV {@link Frame} — both directions.
 *
 * <p>{@link #toFrame}/{@link #bgr24ToFrame}/{@link #jpegToFrame} go <b>into</b> JavaCV, ready to
 * hand to an {@link org.bytedeco.javacv.FFmpegFrameRecorder}. Per the platform's decision that
 * decoded BGR24 is the internal pixel currency (see {@code docs/plans/done/PHASE1-PLAN.md} §0.2), only two
 * source formats are understood: {@link com.drones.vision.domain.model.PixelFormat#BGR24} is
 * copied directly, and {@link com.drones.vision.domain.model.PixelFormat#JPEG} (produced by the
 * simulation source) is decoded via {@link ImageIO} and re-packed as BGR.
 *
 * <p>{@link #copyBgr24(Frame)} goes the <b>other</b> direction — a {@link Frame} decoded by an
 * {@link org.bytedeco.javacv.FFmpegFrameGrabber} out to a tightly packed {@link ByteBuffer} ready
 * to wrap in a {@link VideoFrame} — for {@link MediamtxReplayFrameExtractor}. It is a deliberate,
 * near-verbatim duplicate of {@code adapter-rtsp}'s own {@code FrameConverter.copyBgr24}: per
 * CLAUDE.md's ArchUnit-enforced dependency rule adapters must never depend on each other, so this
 * ~30-line stride-copy is copy-pasted rather than factored into a shared module, the same trade-off
 * already made for this class's native-log-quieting block and {@code TrainingFrameEncoder}'s own
 * javadoc.
 *
 * <p>Package-private and stateless so it is directly unit-testable without a
 * network or a real recorder/grabber, mirroring {@code adapter-rtsp}'s converter.
 */
final class FrameConverter {

    private FrameConverter() {
    }

    /**
     * Converts one frame, allocating a fresh {@link Frame} with its own
     * native backing buffer (never aliases {@code videoFrame}'s buffer).
     *
     * @param videoFrame the frame to convert; must carry {@code BGR24} or {@code JPEG} pixel data
     * @return a BGR, 3-channel, 8-bit {@link Frame} of the same dimensions
     * @throws IOException if {@code videoFrame} is JPEG-encoded and cannot be decoded
     */
    static Frame toFrame(VideoFrame videoFrame) throws IOException {
        return switch (videoFrame.format()) {
            case BGR24 -> bgr24ToFrame(videoFrame);
            case JPEG -> jpegToFrame(videoFrame);
            default -> throw new IllegalArgumentException(
                    "MediamtxStreamPublisher cannot publish pixel format " + videoFrame.format()
                            + " (only BGR24 and JPEG are supported)");
        };
    }

    /** Copies a packed BGR24 buffer into a new {@link Frame}, row by row to respect JavaCV's padded stride. */
    static Frame bgr24ToFrame(VideoFrame videoFrame) {
        int width = videoFrame.width();
        int height = videoFrame.height();
        Frame frame = new Frame(width, height, Frame.DEPTH_UBYTE, 3);
        copyPackedRowsInto((ByteBuffer) frame.image[0], videoFrame.data(), width * 3, height, frame.imageStride);
        return frame;
    }

    /** Decodes a JPEG buffer via {@link ImageIO} and re-packs the pixels as BGR into a new {@link Frame}. */
    static Frame jpegToFrame(VideoFrame videoFrame) throws IOException {
        ByteBuffer src = videoFrame.data();
        byte[] jpegBytes = new byte[src.remaining()];
        src.get(jpegBytes);

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpegBytes));
        if (image == null) {
            throw new IOException("ImageIO could not decode JPEG frame for stream "
                    + videoFrame.streamId().value() + " (sequence " + videoFrame.sequence() + ")");
        }

        int width = image.getWidth();
        int height = image.getHeight();
        Frame frame = new Frame(width, height, Frame.DEPTH_UBYTE, 3);
        ByteBuffer dst = (ByteBuffer) frame.image[0];
        int[] argbRow = image.getRGB(0, 0, width, height, null, 0, width);

        for (int y = 0; y < height; y++) {
            dst.position(y * frame.imageStride);
            int rowOffset = y * width;
            for (int x = 0; x < width; x++) {
                int argb = argbRow[rowOffset + x];
                dst.put((byte) argb);         // B
                dst.put((byte) (argb >>> 8));  // G
                dst.put((byte) (argb >>> 16)); // R
            }
        }
        dst.rewind();
        return frame;
    }

    /**
     * Copies {@code height} rows of {@code rowBytes} packed bytes each from {@code src} into {@code dst},
     * starting each row at its {@code strideBytes}-aligned offset (JavaCV pads each row up to a multiple
     * of 8 bytes, so a flat copy would corrupt every row after the first once {@code rowBytes} isn't
     * itself a multiple of 8).
     */
    private static void copyPackedRowsInto(ByteBuffer dst, ByteBuffer src, int rowBytes, int height, int strideBytes) {
        byte[] row = new byte[rowBytes];
        for (int y = 0; y < height; y++) {
            src.get(row);
            dst.position(y * strideBytes);
            dst.put(row);
        }
        dst.rewind();
    }

    /**
     * Copies a decoded video frame's pixel data (a {@link Frame} produced by an {@link
     * org.bytedeco.javacv.FFmpegFrameGrabber}, e.g. via {@code
     * FFmpegFrameGrabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24)}) into a tightly packed BGR24
     * buffer — the reverse direction of {@link #bgr24ToFrame}, and a near-verbatim duplicate of
     * {@code adapter-rtsp}'s own {@code FrameConverter.copyBgr24} (see this class's javadoc for
     * why the duplication is deliberate).
     *
     * <p>{@link Frame#image} is backed by native memory the grabber reuses on every subsequent
     * {@code grab()}/{@code grabImage()} call, so the bytes must be copied out before they can be
     * handed to code that outlives the current grab call — in particular before wrapping them in a
     * {@link VideoFrame}, whose compact constructor stores the buffer for the lifetime of that
     * immutable record.
     *
     * @param frame a video frame decoded to 3-channel, 8-bit-per-sample BGR
     *              pixel data
     * @return a new heap buffer, positioned at 0 with limit at capacity,
     *         containing exactly {@code frame.imageWidth * frame.imageHeight * 3}
     *         bytes of tightly packed BGR24 pixel data (any row padding stripped)
     * @throws IllegalArgumentException if the frame has no image payload, invalid
     *                                  dimensions, is not 3-channel, or its image
     *                                  buffer is not byte-backed
     */
    static ByteBuffer copyBgr24(Frame frame) {
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        if (frame.image == null || frame.image.length == 0 || frame.image[0] == null) {
            throw new IllegalArgumentException("frame has no image data to copy");
        }
        if (frame.imageWidth <= 0 || frame.imageHeight <= 0) {
            throw new IllegalArgumentException(
                    "frame has invalid dimensions: " + frame.imageWidth + "x" + frame.imageHeight);
        }
        if (frame.imageChannels != 3) {
            throw new IllegalArgumentException(
                    "expected a 3-channel BGR24 frame, got " + frame.imageChannels + " channel(s)");
        }
        Buffer rawImage = frame.image[0];
        if (!(rawImage instanceof ByteBuffer)) {
            throw new IllegalArgumentException(
                    "expected byte-backed image data, got " + rawImage.getClass().getSimpleName());
        }
        ByteBuffer source = (ByteBuffer) rawImage;

        int width = frame.imageWidth;
        int height = frame.imageHeight;
        int rowBytes = width * frame.imageChannels;
        int stride = frame.imageStride > 0 ? frame.imageStride : rowBytes;

        ByteBuffer copy = ByteBuffer.allocate(rowBytes * height);
        byte[] row = new byte[rowBytes];
        for (int y = 0; y < height; y++) {
            // Absolute bulk get: does not touch source's position/limit/mark,
            // so it is safe regardless of what state the caller left it in.
            source.get(y * stride, row, 0, rowBytes);
            copy.put(row);
        }
        copy.flip();
        return copy;
    }
}

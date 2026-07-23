package com.drones.vision.adapter.rtsp;

import com.drones.vision.domain.model.PixelFormat;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;
import com.drones.vision.domain.port.out.VideoSourcePort;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link VideoSourcePort} implementation backed by JavaCV/FFmpeg — the
 * platform's FFmpeg ingest adapter. Covers two protocols:
 * <ul>
 *   <li>{@code rtsp} — real RTSP/RTP camera streams (IP cameras, drone
 *       companions)</li>
 *   <li>{@code file} — a local video file played back as a simulated live
 *       source (drone simulation with zero hardware): looped on request and
 *       paced to its own native frame rate rather than decoded flat out</li>
 * </ul>
 *
 * <p>Supports {@link StreamDescriptor#protocol()} {@code "rtsp"} (any URI)
 * and {@code "file"} (the {@link StreamDescriptor#uri()} scheme must itself
 * be {@code file}). Recognized {@link StreamDescriptor#options()} keys (all
 * optional):
 * <ul>
 *   <li>{@code rtsp_transport} — FFmpeg's {@code rtsp_transport} AVOption
 *       (e.g. {@code tcp}, {@code udp}); default {@value #DEFAULT_RTSP_TRANSPORT}.
 *       Only applied when the URI scheme is {@code rtsp}.</li>
 *   <li>{@code timeout} — socket/read timeout in <b>microseconds</b>, applied
 *       to both the RTSP demuxer's {@code timeout} option and the generic
 *       I/O {@code rw_timeout} option; default {@value #DEFAULT_TIMEOUT_MICROS}
 *       (10 seconds). Only applied when the URI scheme is {@code rtsp}.</li>
 *   <li>{@code loop} — {@code "true"}/{@code "false"}, default {@code false}
 *       (malformed values fall back to the default). When {@code true}, a
 *       graceful end-of-stream ({@link FFmpegFrameGrabber#grab()} returning
 *       {@code null}) restarts the grabber instead of completing the
 *       publisher, so a finite file loops indefinitely until {@link
 *       #close(StreamId)} is called; the frame {@link VideoFrame#sequence()}
 *       keeps increasing monotonically across loop restarts, it never
 *       resets.</li>
 * </ul>
 *
 * <p><b>Real-time pacing:</b> decoding a local file is disk-bound, not
 * time-bound, so left unthrottled it would blast through an entire clip far
 * faster than a live camera ever could. Whenever the URI scheme is {@code
 * file}, the grab loop paces itself against the media timeline: it tracks
 * the delta between successive {@link FFmpegFrameGrabber#getTimestamp()}
 * values (microseconds) and sleeps the difference between that delta and
 * the wall-clock time actually spent since the previous frame, clamped to
 * zero (never a negative sleep, and no drift compensation beyond this one
 * monotonic baseline). The baseline resets on every loop restart, so the
 * first frame of each pass through the file is never delayed. {@code rtsp}
 * URIs are never paced — the network already paces a live camera.
 *
 * <p>Each {@link #open(StreamId, StreamDescriptor)} call starts one
 * dedicated platform thread (decoding is CPU-bound; virtual threads buy
 * nothing here) running a blocking {@link FFmpegFrameGrabber} loop. Every
 * grabbed video frame is decoded to {@link PixelFormat#BGR24}, copied into a
 * heap {@link ByteBuffer} (the grabber reuses its native buffers across
 * calls — see {@link FrameConverter}), wrapped in a {@link VideoFrame}, and
 * offered to a per-stream {@link SubmissionPublisher} with a small buffer
 * and a drop-on-backpressure policy so a slow subscriber never blocks
 * capture (frames the grabber produces while demand is exhausted are
 * dropped rather than queued without bound, per {@link VideoSourcePort}'s
 * latest-wins contract). An unrecoverable grab failure closes the publisher
 * exceptionally and releases the grabber; {@link #close(StreamId)} is the
 * cooperative counterpart (stop flag, thread join, grabber release) and is
 * idempotent.
 *
 * <p>Plain class with no framework dependency — instantiated directly by
 * {@code vision-app}'s wiring configuration.
 */
public final class FfmpegVideoSource implements VideoSourcePort {

    private static final String PROTOCOL_RTSP = "rtsp";
    private static final String PROTOCOL_FILE = "file";

    static final String OPTION_RTSP_TRANSPORT = "rtsp_transport";
    static final String DEFAULT_RTSP_TRANSPORT = "tcp";
    static final String OPTION_TIMEOUT_MICROS = "timeout";
    static final String DEFAULT_TIMEOUT_MICROS = "10000000"; // 10s, in microseconds
    static final String OPTION_LOOP = "loop";
    static final boolean DEFAULT_LOOP = false;

    private static final int PUBLISHER_BUFFER_CAPACITY = 4;
    private static final long CLOSE_JOIN_TIMEOUT_MILLIS = 20_000L;

    private final Map<StreamId, StreamRuntime> runtimes = new ConcurrentHashMap<>();

    public FfmpegVideoSource() {
        ensureQuietLogging();
    }

    // -- native log quieting --------------------------------------------------
    // FFmpeg's native library is chatty by default: every FFmpegFrameGrabber
    // start dumps an AV_LOG_INFO codec/format banner straight to the
    // process's stdout/stderr -- easily mistaken for an error by anyone
    // watching application logs. We only want warnings and worse from the
    // native layer, so the native log threshold is lowered once per JVM,
    // before the first grabber use.
    //
    // Deliberately duplicated (not shared) in adapter-rtsp and
    // adapter-publish-hls: per CLAUDE.md's dependency rule, adapters must
    // not depend on each other, so this ~5-line block is intentionally
    // copy-pasted rather than factored into a shared utility module. See
    // adapter-publish-hls's MediamtxStreamPublisher for its twin.
    //
    // org.bytedeco.javacv.FFmpegLogCallback.set() (routing native logs
    // through javacpp's Logger, java.util.logging-flavored) was evaluated
    // and rejected: an empirical check (a standalone FFmpegFrameRecorder
    // harness against this module's pinned FFmpeg 6.1.1/JavaCV 1.5.10)
    // showed the callback does no level filtering of its own -- it still
    // requires this exact av_log_set_level(WARNING) call to suppress
    // anything -- and once active, it fragments FFmpeg's own multi-part log
    // lines (e.g. the muxer's "Output #0 ..." block) into a stream of
    // separately-prefixed partial lines, which is objectively worse than
    // the default callback's coherent raw output, for no offsetting
    // benefit (this codebase has no JUL-to-Spring bridge configured, and
    // adapter-publish-hls logs via System.Logger, not java.util.logging
    // anyway). Plain av_log_set_level keeps FFmpeg's well-formed default
    // formatting and only changes the threshold.
    private static boolean quietLoggingConfigured = false;

    /**
     * Idempotent; lowers FFmpeg's native log threshold to {@code
     * AV_LOG_ERROR} so grabber start/stop no longer dumps INFO-level
     * banners to stdout/stderr -- only warnings and errors from the native
     * layer still print. Safe to call repeatedly (e.g. once per constructed
     * instance, including across many instances in a single test run): the
     * guard makes every call after the first a no-op.
     */
    static synchronized void ensureQuietLogging() {
        if (quietLoggingConfigured) {
            return;
        }
        // ERROR, not WARNING: decoding any yuvj-tagged media (MJPEG streams, many
        // mp4s) makes swscale print "deprecated pixel format used, make sure you
        // did set range correctly" once per converted frame -- a known-benign
        // warning that JavaCV's high-level API offers no per-context way to avoid.
        avutil.av_log_set_level(avutil.AV_LOG_ERROR);
        quietLoggingConfigured = true;
    }

    @Override
    public boolean supports(StreamDescriptor descriptor) {
        if (descriptor == null) {
            return false;
        }
        String protocol = descriptor.protocol();
        if (PROTOCOL_RTSP.equals(protocol)) {
            return true;
        }
        if (PROTOCOL_FILE.equals(protocol)) {
            URI uri = descriptor.uri();
            return uri != null && PROTOCOL_FILE.equalsIgnoreCase(uri.getScheme());
        }
        return false;
    }

    @Override
    public Flow.Publisher<VideoFrame> open(StreamId id, StreamDescriptor descriptor) {
        if (!supports(descriptor)) {
            throw new IllegalArgumentException("FfmpegVideoSource does not support descriptor: " + descriptor);
        }
        return openAny(id, descriptor.uri(), descriptor.options());
    }

    /**
     * Test seam: runs the exact production grab loop against any URI (an
     * {@code rtsp://} camera, a local {@code file:} video, ...) without the
     * {@link #supports(StreamDescriptor)} protocol check. This lets
     * integration tests exercise the real FFmpeg decode path against a
     * small local file without a live camera.
     *
     * @param id      identity to associate with the opened stream
     * @param uri     resource to open; RTSP-specific grabber options and
     *                real-time pacing (see class javadoc) are only applied
     *                based on {@code uri.getScheme()}
     * @param options adapter options, see class javadoc; may be empty
     * @return a per-open publisher of frames; see {@link VideoSourcePort} for
     *         delivery/backpressure semantics
     */
    Flow.Publisher<VideoFrame> openAny(StreamId id, URI uri, Map<String, String> options) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (uri == null) {
            throw new IllegalArgumentException("uri must not be null");
        }
        Map<String, String> effectiveOptions = options == null ? Map.of() : options;
        StreamRuntime runtime = new StreamRuntime(id, uri, effectiveOptions);
        StreamRuntime previous = runtimes.put(id, runtime);
        if (previous != null) {
            previous.close(); // defensive: an id must not have two live runtimes
        }
        runtime.start();
        return runtime.publisher;
    }

    @Override
    public void close(StreamId id) {
        StreamRuntime runtime = runtimes.remove(id);
        if (runtime != null) {
            runtime.close();
        }
    }

    /** Per-open runtime: a dedicated grab thread feeding a {@link SubmissionPublisher}. */
    private static final class StreamRuntime {
        private final StreamId streamId;
        private final URI uri;
        private final Map<String, String> options;
        private final boolean loop;
        private final boolean paced;
        private final SubmissionPublisher<VideoFrame> publisher =
                new SubmissionPublisher<>(ForkJoinPool.commonPool(), PUBLISHER_BUFFER_CAPACITY);
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicBoolean stopRequested = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile Thread grabThread;

        StreamRuntime(StreamId streamId, URI uri, Map<String, String> options) {
            this.streamId = streamId;
            this.uri = uri;
            this.options = options;
            this.loop = booleanOption(options, OPTION_LOOP, DEFAULT_LOOP);
            this.paced = PROTOCOL_FILE.equalsIgnoreCase(uri.getScheme());
        }

        void start() {
            grabThread = new Thread(this::runGrabLoop, "rtsp-video-" + streamId.value());
            grabThread.setDaemon(true);
            grabThread.start();
        }

        private void runGrabLoop() {
            FFmpegFrameGrabber grabber = null;
            boolean errored = false;
            try {
                grabber = newGrabber();
                grabber.start();
                // Real-time pacing baseline (file sources only, see class javadoc):
                // -1 means "no previous frame yet" -- the next grabbed frame sets the
                // baseline without sleeping, whether that is the very first frame or
                // the first frame after a loop restart.
                long pacingBaselineTimestampMicros = -1;
                long pacingBaselineWallNanos = 0;
                while (!stopRequested.get()) {
                    Frame frame = grabber.grab();
                    if (frame == null) {
                        if (loop && !stopRequested.get()) {
                            grabber.restart(); // stop() + start(): reopens the file from the beginning
                            pacingBaselineTimestampMicros = -1; // reset pacing baseline across the loop restart
                            continue;
                        }
                        break; // end of stream (e.g. a file source ran out) -- graceful completion
                    }
                    if (paced) {
                        long timestampMicros = grabber.getTimestamp();
                        if (pacingBaselineTimestampMicros >= 0) {
                            long targetDeltaMicros = timestampMicros - pacingBaselineTimestampMicros;
                            long elapsedMicros = (System.nanoTime() - pacingBaselineWallNanos) / 1_000L;
                            sleepMicros(targetDeltaMicros - elapsedMicros);
                        }
                        pacingBaselineTimestampMicros = timestampMicros;
                        pacingBaselineWallNanos = System.nanoTime();
                    }
                    if (frame.image == null || frame.image.length == 0) {
                        continue; // audio/data-only frame: no pixel payload to publish
                    }
                    ByteBuffer copy = FrameConverter.copyBgr24(frame);
                    VideoFrame videoFrame = new VideoFrame(streamId, sequence.getAndIncrement(), Instant.now(),
                            frame.imageWidth, frame.imageHeight, PixelFormat.BGR24, copy);
                    // Latest-wins backpressure: never block capture for a slow subscriber;
                    // when a subscriber's small buffer is full, the offered frame is dropped
                    // instead of queuing capture indefinitely.
                    publisher.offer(videoFrame, (subscriber, dropped) -> true);
                }
            } catch (Exception e) {
                errored = true;
                if (!stopRequested.get()) {
                    // Unrecoverable grab failure: signal onError and stop producing frames.
                    publisher.closeExceptionally(e);
                }
            } finally {
                releaseQuietly(grabber);
                if (!errored) {
                    publisher.close();
                }
            }
        }

        private FFmpegFrameGrabber newGrabber() {
            FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(resolveFilename(uri));
            grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24);
            if (PROTOCOL_RTSP.equalsIgnoreCase(uri.getScheme())) {
                String transport = options.getOrDefault(OPTION_RTSP_TRANSPORT, DEFAULT_RTSP_TRANSPORT);
                grabber.setOption(OPTION_RTSP_TRANSPORT, transport);
                String timeoutMicros = options.getOrDefault(OPTION_TIMEOUT_MICROS, DEFAULT_TIMEOUT_MICROS);
                grabber.setOption(OPTION_TIMEOUT_MICROS, timeoutMicros);
                grabber.setOption("rw_timeout", timeoutMicros);
            }
            return grabber;
        }

        void close() {
            if (closed.compareAndSet(false, true)) {
                stopRequested.set(true);
                Thread thread = grabThread;
                if (thread != null && thread != Thread.currentThread()) {
                    thread.interrupt(); // best-effort; native grab() may not respond to this
                    try {
                        thread.join(CLOSE_JOIN_TIMEOUT_MILLIS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                publisher.close();
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

        private static String resolveFilename(URI uri) {
            if (PROTOCOL_FILE.equalsIgnoreCase(uri.getScheme())) {
                return Paths.get(uri).toString();
            }
            return uri.toString();
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

        /**
         * Lenient boolean option parsing, matching this module's existing
         * idiom for other options: missing/blank falls back to {@code
         * defaultValue}, and so does anything that isn't (case-insensitively)
         * {@code "true"} or {@code "false"} -- a malformed value is never
         * allowed to crash stream setup.
         */
        private static boolean booleanOption(Map<String, String> options, String key, boolean defaultValue) {
            String raw = options.get(key);
            if (raw == null || raw.isBlank()) {
                return defaultValue;
            }
            String trimmed = raw.trim();
            if ("true".equalsIgnoreCase(trimmed)) {
                return true;
            }
            if ("false".equalsIgnoreCase(trimmed)) {
                return false;
            }
            return defaultValue; // malformed: keep the default rather than guessing
        }
    }
}

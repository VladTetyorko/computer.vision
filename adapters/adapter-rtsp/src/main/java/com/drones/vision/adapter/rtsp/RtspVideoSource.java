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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link VideoSourcePort} implementation for RTSP/RTP camera streams (IP
 * cameras, drone companions), backed by JavaCV/FFmpeg.
 *
 * <p>Supports {@link StreamDescriptor#protocol()} {@code "rtsp"}. Recognized
 * {@link StreamDescriptor#options()} keys (all optional):
 * <ul>
 *   <li>{@code rtsp_transport} — FFmpeg's {@code rtsp_transport} AVOption
 *       (e.g. {@code tcp}, {@code udp}); default {@value #DEFAULT_RTSP_TRANSPORT}</li>
 *   <li>{@code timeout} — socket/read timeout in <b>microseconds</b>, applied
 *       to both the RTSP demuxer's {@code timeout} option and the generic
 *       I/O {@code rw_timeout} option; default {@value #DEFAULT_TIMEOUT_MICROS}
 *       (10 seconds)</li>
 * </ul>
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
public final class RtspVideoSource implements VideoSourcePort {

    private static final String PROTOCOL = "rtsp";

    static final String OPTION_RTSP_TRANSPORT = "rtsp_transport";
    static final String DEFAULT_RTSP_TRANSPORT = "tcp";
    static final String OPTION_TIMEOUT_MICROS = "timeout";
    static final String DEFAULT_TIMEOUT_MICROS = "10000000"; // 10s, in microseconds

    private static final int PUBLISHER_BUFFER_CAPACITY = 4;
    private static final long CLOSE_JOIN_TIMEOUT_MILLIS = 20_000L;

    private final Map<StreamId, StreamRuntime> runtimes = new ConcurrentHashMap<>();

    public RtspVideoSource() {
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
     * AV_LOG_WARNING} so grabber start/stop no longer dumps INFO-level
     * banners to stdout/stderr -- only warnings and errors from the native
     * layer still print. Safe to call repeatedly (e.g. once per constructed
     * instance, including across many instances in a single test run): the
     * guard makes every call after the first a no-op.
     */
    static synchronized void ensureQuietLogging() {
        if (quietLoggingConfigured) {
            return;
        }
        avutil.av_log_set_level(avutil.AV_LOG_WARNING);
        quietLoggingConfigured = true;
    }

    @Override
    public boolean supports(StreamDescriptor descriptor) {
        return descriptor != null && PROTOCOL.equals(descriptor.protocol());
    }

    @Override
    public Flow.Publisher<VideoFrame> open(StreamId id, StreamDescriptor descriptor) {
        if (!supports(descriptor)) {
            throw new IllegalArgumentException("RtspVideoSource does not support descriptor: " + descriptor);
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
     * @param uri     resource to open; RTSP-specific grabber options (see
     *                class javadoc) are only applied when {@code uri.getScheme()}
     *                is {@code "rtsp"}
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
                while (!stopRequested.get()) {
                    Frame frame = grabber.grab();
                    if (frame == null) {
                        break; // end of stream (e.g. a file source ran out) -- graceful completion
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
            if ("rtsp".equalsIgnoreCase(uri.getScheme())) {
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
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                return Paths.get(uri).toString();
            }
            return uri.toString();
        }
    }
}

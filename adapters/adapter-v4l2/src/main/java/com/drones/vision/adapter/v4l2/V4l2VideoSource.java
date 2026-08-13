package com.drones.vision.adapter.v4l2;

import com.drones.vision.perception.domain.model.PixelFormat;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.VideoFrame;
import com.drones.vision.perception.domain.port.VideoSourcePort;
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
 * {@link VideoSourcePort} implementation for local USB/V4L2 cameras
 * (docs/plans/done/MVP2-PLAN.md X-b), via JavaCV/FFmpeg's {@code v4l2} demuxer.
 *
 * <p><b>RX only.</b> A local capture device has no wire to transmit
 * <i>to</i> — the same "no TX half" situation as {@code adapter-simulation}'s
 * {@code sim} source and {@code adapter-rtsp}'s {@code file} source (see
 * {@code docs/main/CYCLES-PLAN.md} §0's RX/TX doctrine). There is no {@code
 * V4l2FeedTransmitter}, and none is planned.
 *
 * <p><b>Protocol/URI shape — matches {@code adapter-discovery}'s {@code
 * V4l2Scanner}, not docs/plans/done/MVP2-PLAN.md X-b's original brief.</b> The brief
 * proposed protocol {@code "usb"} with {@code uri = v4l2:///dev/videoN}; the
 * real {@code V4l2Scanner} (already shipped, verified by reading its source
 * and its test) emits {@link StreamDescriptor#protocol()} {@code "v4l2"} with
 * a {@code file:} URI ({@code file:/dev/videoN}) — the actual device node
 * path, using the {@code file} scheme the same way {@code adapter-rtsp}'s
 * {@code file} protocol does. Discovery results flow straight into device
 * registration unchanged ({@code DiscoveredDeviceResponse} flattens {@code
 * suggestedStream} verbatim; nothing rewrites the protocol/URI in between),
 * so for "Discover → register → stream" to actually work end to end this
 * class's {@link #supports(StreamDescriptor)} accepts <b>exactly</b> that
 * shape — {@code protocol = "v4l2"}, URI scheme {@code file} — rather than
 * the brief's originally-imagined one. See this class's own MODULE.md for
 * the full writeup.
 *
 * <p>Recognized {@link StreamDescriptor#options()} keys (all optional — FFmpeg's
 * {@code v4l2} demuxer AVOptions, passed straight through, no defaults; an unset
 * option lets the driver's own default win):
 * <ul>
 *   <li>{@code video_size} — capture resolution, e.g. {@code "1280x720"}</li>
 *   <li>{@code framerate} — capture frame rate, e.g. {@code "30"}</li>
 *   <li>{@code input_format} — raw capture pixel format the driver is asked
 *       for, e.g. {@code "mjpeg"}, {@code "yuyv422"} (independent of the
 *       {@code BGR24} format this class always decodes <i>to</i> before
 *       publishing, see below)</li>
 * </ul>
 *
 * <p>Unlike {@code adapter-rtsp}'s {@code rtsp} protocol, no {@code timeout}/
 * {@code rw_timeout} option is offered: those are AVOptions of network-facing
 * FFmpeg protocols (RTSP demuxer, generic avio), and the {@code v4l2} demuxer
 * reads a local character device directly, not through avio — there is no
 * equivalent "stuck socket" failure mode to bound. A mid-stream USB unplug
 * surfaces as an ordinary I/O error from the driver on the next read, which
 * this class's grab loop already treats like any other unrecoverable grab
 * failure (see below) — no separate watchdog needed.
 *
 * <p>Each {@link #open(StreamId, StreamDescriptor)} call starts one dedicated
 * platform thread (decoding is CPU-bound; virtual threads buy nothing here)
 * running a blocking {@link FFmpegFrameGrabber} loop against format {@code
 * "v4l2"}. Every grabbed video frame is decoded to {@link PixelFormat#BGR24},
 * copied into a heap {@link ByteBuffer} (the grabber reuses its native
 * buffers across calls — see {@link V4l2FrameConverter}), wrapped in a {@link
 * VideoFrame}, and offered to a per-stream {@link SubmissionPublisher} with a
 * small buffer and a drop-on-backpressure policy so a slow subscriber never
 * blocks capture, per {@link VideoSourcePort}'s latest-wins contract. An
 * unrecoverable grab failure (including a mid-stream unplug) closes the
 * publisher exceptionally and releases the grabber; {@link #close(StreamId)}
 * is the cooperative counterpart (stop flag, thread interrupt/join, grabber
 * release) and is idempotent.
 *
 * <p>Plain class with no framework dependency — instantiated directly by
 * {@code vision-app}'s wiring configuration. The no-arg constructor uses
 * {@link #PUBLISHER_BUFFER_CAPACITY}/{@link #CLOSE_JOIN_TIMEOUT_MILLIS} as
 * defaults; the two-arg constructor lets a caller (e.g. {@code vision-app},
 * bound from {@code vision.v4l2.*} properties) override either. Only two
 * tunables exist today, so — per {@code docs/plans/active/LAYERING-REFACTOR-PLAN.md}
 * §1.3 rule 4 — this class takes them as plain constructor parameters
 * rather than a settings record.
 */
public final class V4l2VideoSource implements VideoSourcePort {

    private static final String PROTOCOL_V4L2 = "v4l2";
    private static final String URI_SCHEME_FILE = "file";
    private static final String FFMPEG_FORMAT_V4L2 = "v4l2";

    static final String OPTION_VIDEO_SIZE = "video_size";
    static final String OPTION_FRAMERATE = "framerate";
    static final String OPTION_INPUT_FORMAT = "input_format";

    /**
     * Default {@code SubmissionPublisher} buffer capacity for every opened
     * stream's drop-newest backpressure (see class javadoc). Overridable per
     * instance via the {@code publisherBufferCapacity} constructor parameter
     * (e.g. {@code vision.v4l2.publisher-buffer-capacity}, vision-app).
     */
    static final int PUBLISHER_BUFFER_CAPACITY = 4;

    /**
     * Default upper bound on how long {@link #close(StreamId)} waits for the
     * grab thread to join after being interrupted, before giving up and
     * releasing the grabber anyway. Overridable per instance via the {@code
     * closeJoinTimeoutMillis} constructor parameter (e.g. {@code
     * vision.v4l2.close-join-timeout}, vision-app).
     */
    static final long CLOSE_JOIN_TIMEOUT_MILLIS = 20_000L;

    private final Map<StreamId, StreamRuntime> runtimes = new ConcurrentHashMap<>();
    private final int publisherBufferCapacity;
    private final long closeJoinTimeoutMillis;

    public V4l2VideoSource() {
        this(PUBLISHER_BUFFER_CAPACITY, CLOSE_JOIN_TIMEOUT_MILLIS);
    }

    /**
     * Canonical constructor: bring your own buffer capacity / close-join
     * timeout (see the two fields' javadoc for what each controls).
     *
     * @param publisherBufferCapacity per-stream {@code SubmissionPublisher}
     *                                buffer capacity; must be {@code >= 1}
     * @param closeJoinTimeoutMillis  how long {@link #close(StreamId)} waits
     *                                for the grab thread to join; must be
     *                                {@code > 0}
     * @throws IllegalArgumentException if either parameter is out of range
     */
    public V4l2VideoSource(int publisherBufferCapacity, long closeJoinTimeoutMillis) {
        if (publisherBufferCapacity < 1) {
            throw new IllegalArgumentException(
                    "publisherBufferCapacity must be >= 1, was " + publisherBufferCapacity);
        }
        if (closeJoinTimeoutMillis <= 0) {
            throw new IllegalArgumentException(
                    "closeJoinTimeoutMillis must be > 0, was " + closeJoinTimeoutMillis);
        }
        this.publisherBufferCapacity = publisherBufferCapacity;
        this.closeJoinTimeoutMillis = closeJoinTimeoutMillis;
        ensureQuietLogging();
    }

    // -- native log quieting --------------------------------------------------
    // Deliberately duplicated (not shared) from adapter-rtsp's FfmpegVideoSource:
    // per CLAUDE.md's dependency rule, adapters must not depend on each other.
    // See FfmpegVideoSource's own copy for the full rationale (native INFO-level
    // banners on every grabber start, and the empirical case for plain
    // av_log_set_level over FFmpegLogCallback.set()).
    private static boolean quietLoggingConfigured = false;

    static synchronized void ensureQuietLogging() {
        if (quietLoggingConfigured) {
            return;
        }
        avutil.av_log_set_level(avutil.AV_LOG_ERROR);
        quietLoggingConfigured = true;
    }

    @Override
    public boolean supports(StreamDescriptor descriptor) {
        if (descriptor == null) {
            return false;
        }
        if (!PROTOCOL_V4L2.equals(descriptor.protocol())) {
            return false;
        }
        URI uri = descriptor.uri();
        return uri != null && URI_SCHEME_FILE.equalsIgnoreCase(uri.getScheme());
    }

    @Override
    public Flow.Publisher<VideoFrame> open(StreamId id, StreamDescriptor descriptor) {
        if (!supports(descriptor)) {
            throw new IllegalArgumentException("V4l2VideoSource does not support descriptor: " + descriptor);
        }
        return openAny(id, descriptor.uri(), descriptor.options());
    }

    /**
     * Test seam: runs the exact production grab loop against any {@code
     * file:} URI naming a device path, without the {@link
     * #supports(StreamDescriptor)} protocol check. Lets tests exercise the
     * real FFmpeg {@code v4l2} decode path (e.g. against a v4l2loopback
     * device) without going through a full {@link StreamDescriptor}.
     *
     * @param id      identity to associate with the opened stream
     * @param uri     a {@code file:} URI naming the device node (e.g. {@code
     *                file:/dev/video0})
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
        StreamRuntime runtime = new StreamRuntime(id, resolveDevicePath(uri), effectiveOptions,
                publisherBufferCapacity, closeJoinTimeoutMillis);
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

    private static String resolveDevicePath(URI uri) {
        return Paths.get(uri).toString();
    }

    /** Per-open runtime: a dedicated grab thread feeding a {@link SubmissionPublisher}. */
    private static final class StreamRuntime {
        private final StreamId streamId;
        private final String devicePath;
        private final Map<String, String> options;
        private final long closeJoinTimeoutMillis;
        private final SubmissionPublisher<VideoFrame> publisher;
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicBoolean stopRequested = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile Thread grabThread;

        StreamRuntime(StreamId streamId, String devicePath, Map<String, String> options,
                int publisherBufferCapacity, long closeJoinTimeoutMillis) {
            this.streamId = streamId;
            this.devicePath = devicePath;
            this.options = options;
            this.closeJoinTimeoutMillis = closeJoinTimeoutMillis;
            this.publisher = new SubmissionPublisher<>(ForkJoinPool.commonPool(), publisherBufferCapacity);
        }

        void start() {
            grabThread = new Thread(this::runGrabLoop, "v4l2-video-" + streamId.value());
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
                    // grabImage(), not grab(): same rationale as adapter-rtsp's FfmpegVideoSource
                    // -- skips non-video packets so a driver that also exposes e.g. metadata
                    // frames never confuses this loop.
                    Frame frame = grabber.grabImage();
                    if (frame == null) {
                        break; // graceful EOF/disconnect signal from the demuxer
                    }
                    if (frame.image == null || frame.image.length == 0) {
                        continue; // non-pixel frame: nothing to publish
                    }
                    ByteBuffer copy = V4l2FrameConverter.copyBgr24(frame);
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
                    // Unrecoverable grab failure (including a mid-stream device unplug):
                    // signal onError and stop producing frames -- never hang.
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
            FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(devicePath);
            grabber.setFormat(FFMPEG_FORMAT_V4L2);
            grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24);
            setOptionIfPresent(grabber, OPTION_VIDEO_SIZE);
            setOptionIfPresent(grabber, OPTION_FRAMERATE);
            setOptionIfPresent(grabber, OPTION_INPUT_FORMAT);
            return grabber;
        }

        private void setOptionIfPresent(FFmpegFrameGrabber grabber, String key) {
            String value = options.get(key);
            if (value != null && !value.isBlank()) {
                grabber.setOption(key, value.trim());
            }
        }

        void close() {
            if (closed.compareAndSet(false, true)) {
                stopRequested.set(true);
                Thread thread = grabThread;
                if (thread != null && thread != Thread.currentThread()) {
                    thread.interrupt(); // best-effort; native grab() may not respond to this
                    try {
                        thread.join(closeJoinTimeoutMillis);
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
    }
}

package com.drones.vision.adapter.publishhls;

import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;
import com.drones.vision.domain.port.out.StreamPublisherPort;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P;

/**
 * {@link StreamPublisherPort} that re-encodes frames to H.264 and pushes
 * them as an RTSP stream to a <a href="https://github.com/bluenviron/mediamtx">mediamtx</a>
 * sidecar; browsers then watch mediamtx's HLS egress. See {@code docs/PHASE1-PLAN.md}
 * §0.1/§0.3 and §3 for the design this class implements.
 *
 * <h2>Lifecycle</h2>
 * {@link #streamStarted(StreamId, Device)} only registers bookkeeping state —
 * the {@link FFmpegFrameRecorder} is created lazily on the first {@link
 * #publish(StreamId, VideoFrame)} call, once the frame's dimensions are
 * known. {@link #streamEnded(StreamId)} releases the recorder and forgets
 * the stream; both are idempotent.
 *
 * <h2>Resilience</h2>
 * Nothing thrown by JavaCV/FFmpeg (or by frame conversion) ever escapes
 * {@link #publish}, {@link #streamStarted}, or {@link #streamEnded}: a
 * broken or absent mediamtx must never take down the owning pipeline. While
 * a stream is broken, frames are dropped and reconnect attempts are
 * throttled with exponential backoff (capped at {@value #MAX_BACKOFF_MS} ms);
 * a single {@code WARNING} is logged per outage (not per dropped frame).
 *
 * <h2>Threading</h2>
 * Per {@link StreamPublisherPort}'s contract, calls for a single {@code
 * streamId} are not concurrent, so per-stream state needs no internal
 * locking; different streams are tracked independently in a {@link
 * ConcurrentHashMap} since their pipelines run on different threads.
 *
 * <p>Plain class with no framework dependency — instantiated directly by
 * {@code vision-app}'s wiring configuration.
 */
public final class MediamtxStreamPublisher implements StreamPublisherPort {

    private static final System.Logger LOG = System.getLogger(MediamtxStreamPublisher.class.getName());

    private static final double DEFAULT_FRAME_RATE_FPS = 15.0;
    private static final int GOP_SECONDS = 2;
    /** Bounds the underlying TCP connect/I/O for the RTSP push, in microseconds, so a dead mediamtx can't hang a publish call. */
    private static final String CONNECT_TIMEOUT_MICROS = "5000000";
    private static final long INITIAL_BACKOFF_MS = 500L;
    private static final long MAX_BACKOFF_MS = 10_000L;
    private static final String HLS_PLAYLIST_SUFFIX = "/index.m3u8";

    private final URI rtspPushBase;
    private final URI hlsViewBase;
    private final Map<StreamId, StreamState> streams = new ConcurrentHashMap<>();

    /**
     * @param rtspPushBase base RTSP URL of the mediamtx sidecar to push to, e.g. {@code rtsp://localhost:8554}
     * @param hlsViewBase  base HTTP URL of mediamtx's HLS egress, e.g. {@code http://localhost:8888}
     */
    public MediamtxStreamPublisher(URI rtspPushBase, URI hlsViewBase) {
        ensureQuietLogging();
        this.rtspPushBase = Objects.requireNonNull(rtspPushBase, "rtspPushBase must not be null");
        this.hlsViewBase = Objects.requireNonNull(hlsViewBase, "hlsViewBase must not be null");
    }

    // -- native log quieting --------------------------------------------------
    // FFmpeg's native library is chatty by default: every FFmpegFrameRecorder
    // start dumps an AV_LOG_INFO libx264 config banner and "Output #0 ..."
    // header, and every stop dumps encoding stats, straight to the
    // process's stdout/stderr -- easily mistaken for an error by anyone
    // watching application logs. We only want warnings and worse from the
    // native layer, so the native log threshold is lowered once per JVM,
    // before the first recorder use.
    //
    // Deliberately duplicated (not shared) in adapter-rtsp and
    // adapter-publish-hls: per CLAUDE.md's dependency rule, adapters must
    // not depend on each other, so this ~5-line block is intentionally
    // copy-pasted rather than factored into a shared utility module. See
    // adapter-rtsp's RtspVideoSource for its twin.
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
    // benefit (this class already logs via System.Logger, not
    // java.util.logging, and there is no JUL-to-Spring bridge configured
    // in this codebase anyway). Plain av_log_set_level keeps FFmpeg's
    // well-formed default formatting and only changes the threshold.
    private static boolean quietLoggingConfigured = false;

    /**
     * Idempotent; lowers FFmpeg's native log threshold to {@code
     * AV_LOG_WARNING} so recorder start/stop no longer dumps INFO-level
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
    public void streamStarted(StreamId id, Device device) {
        if (id == null) {
            return;
        }
        StreamState previous = streams.put(id, new StreamState());
        releaseQuietly(previous == null ? null : previous.recorder);
        LOG.log(System.Logger.Level.INFO, () -> "Publishing stream " + id.value()
                + (device != null ? " (" + device.name() + ")" : "") + " to " + pushUrl(id));
    }

    @Override
    public void publish(StreamId id, VideoFrame frame) {
        if (id == null || frame == null) {
            return;
        }
        StreamState state = streams.computeIfAbsent(id, unused -> new StreamState());
        if (state.recorder == null && !state.readyToRetry()) {
            return; // still backing off from a previous failure; drop silently, no attempt yet
        }
        try {
            doPublish(id, frame, state);
            onPublishSucceeded(id, state);
        } catch (Exception e) {
            onPublishFailed(id, state, e);
        }
    }

    @Override
    public void streamEnded(StreamId id) {
        if (id == null) {
            return;
        }
        StreamState state = streams.remove(id);
        if (state == null) {
            return;
        }
        FFmpegFrameRecorder recorder = state.recorder;
        state.recorder = null;
        if (recorder == null) {
            return;
        }
        try {
            recorder.stop();
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Error stopping mediamtx recorder for stream " + id.value(), e);
        } finally {
            releaseQuietly(recorder);
        }
        LOG.log(System.Logger.Level.INFO, () -> "Stopped publishing stream " + id.value());
    }

    @Override
    public Optional<URI> viewUrl(StreamId id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.of(URI.create(withoutTrailingSlash(hlsViewBase.toString()) + "/" + id.value() + HLS_PLAYLIST_SUFFIX));
    }

    // -- publish machinery --------------------------------------------------

    private void doPublish(StreamId id, VideoFrame videoFrame, StreamState state) throws Exception {
        Frame frame = FrameConverter.toFrame(videoFrame);

        FFmpegFrameRecorder recorder = state.recorder;
        if (recorder == null) {
            recorder = startRecorder(id, frame.imageWidth, frame.imageHeight);
            state.recorder = recorder;
        }

        recorder.setTimestamp(state.nextTimestampMicros(videoFrame.capturedAt(), DEFAULT_FRAME_RATE_FPS));
        recorder.record(frame);
    }

    private FFmpegFrameRecorder startRecorder(StreamId id, int width, int height) throws Exception {
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(pushUrl(id), width, height);
        recorder.setFormat("rtsp");
        recorder.setOption("rtsp_transport", "tcp");
        recorder.setOption("timeout", CONNECT_TIMEOUT_MICROS);
        recorder.setVideoCodec(AV_CODEC_ID_H264);
        recorder.setVideoCodecName("libx264");
        recorder.setVideoOption("preset", "ultrafast");
        recorder.setVideoOption("tune", "zerolatency");
        recorder.setFrameRate(DEFAULT_FRAME_RATE_FPS);
        recorder.setGopSize((int) Math.round(DEFAULT_FRAME_RATE_FPS * GOP_SECONDS));
        recorder.setPixelFormat(AV_PIX_FMT_YUV420P);
        try {
            recorder.start();
        } catch (Exception e) {
            releaseQuietly(recorder);
            throw e;
        }
        return recorder;
    }

    private String pushUrl(StreamId id) {
        return withoutTrailingSlash(rtspPushBase.toString()) + "/" + id.value();
    }

    private void onPublishSucceeded(StreamId id, StreamState state) {
        if (state.endOutage()) {
            LOG.log(System.Logger.Level.INFO, () -> "Resumed publishing stream " + id.value() + " to mediamtx");
        }
    }

    private void onPublishFailed(StreamId id, StreamState state, Exception e) {
        releaseQuietly(state.recorder);
        state.recorder = null;
        if (state.beginOutage()) {
            LOG.log(System.Logger.Level.WARNING,
                    "Failed to publish stream " + id.value() + " to mediamtx at " + pushUrl(id)
                            + "; will keep retrying with backoff and drop frames until it recovers", e);
        }
        state.scheduleRetry();
    }

    private static void releaseQuietly(FFmpegFrameRecorder recorder) {
        if (recorder == null) {
            return;
        }
        try {
            recorder.release();
        } catch (Exception ignored) {
            // best-effort cleanup; nothing more we can do
        }
    }

    private static String withoutTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    // -- per-stream state -----------------------------------------------------

    /** Mutable bookkeeping for one active stream. Not thread-safe by design; see class javadoc. */
    private static final class StreamState {

        volatile FFmpegFrameRecorder recorder;

        private boolean outage;
        private long backoffMs = INITIAL_BACKOFF_MS;
        private long nextRetryAtEpochMs = 0L;

        private Instant firstCapturedAt;
        private long lastFrameNumber = -1L;

        boolean readyToRetry() {
            return System.currentTimeMillis() >= nextRetryAtEpochMs;
        }

        void scheduleRetry() {
            nextRetryAtEpochMs = System.currentTimeMillis() + backoffMs;
            backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
        }

        /** @return {@code true} the first time this is called for a given outage (so the caller logs once) */
        boolean beginOutage() {
            boolean first = !outage;
            outage = true;
            return first;
        }

        /** @return {@code true} if this call ends an active outage (so the caller can log recovery) */
        boolean endOutage() {
            boolean wasDown = outage;
            outage = false;
            backoffMs = INITIAL_BACKOFF_MS;
            nextRetryAtEpochMs = 0L;
            return wasDown;
        }

        /**
         * Monotonic, never-backwards microsecond timestamp relative to this
         * stream's first published frame — quantized so it can never collide
         * with the previous frame once FFmpeg gets hold of it.
         *
         * <p>{@link FFmpegFrameRecorder#setTimestamp(long)} does not use the
         * microsecond value verbatim: it rounds it down to a whole video
         * frame number at {@code frameRateFps}
         * ({@code round(timestampMicros * frameRateFps / 1_000_000)}) before
         * handing that integer to the muxer as the packet's PTS/DTS. Two
         * frames whose {@code capturedAt} are less than one frame period
         * apart — routine with bursty {@link java.util.concurrent.SubmissionPublisher}
         * delivery (e.g. a scheduler catching up after a stall) — round to
         * the *same* frame number, and the muxer rejects the second write
         * with {@code av_interleaved_write_frame() error -22} (EINVAL:
         * non-monotonic/duplicate DTS). Bumping the microsecond value by one
         * (the naive fix) does not help — it still rounds to the same frame
         * number. So this tracks the last *frame number* actually emitted
         * and, whenever the naturally-computed one would collide, advances
         * to the next free frame slot and returns the microsecond value that
         * maps back to it (keeping {@link #recorder}'s {@code
         * setFrameRate} in lockstep with this cadence policy).
         */
        long nextTimestampMicros(Instant capturedAt, double frameRateFps) {
            if (firstCapturedAt == null) {
                firstCapturedAt = capturedAt;
            }
            long relativeMicros = Duration.between(firstCapturedAt, capturedAt).toNanos() / 1000L;
            long frameNumber = Math.round(relativeMicros * frameRateFps / 1_000_000.0);
            if (frameNumber <= lastFrameNumber) {
                frameNumber = lastFrameNumber + 1;
                relativeMicros = Math.round(frameNumber * 1_000_000.0 / frameRateFps);
            }
            lastFrameNumber = frameNumber;
            return relativeMicros;
        }
    }
}

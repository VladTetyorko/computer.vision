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
import java.util.Arrays;
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
 * the {@link FFmpegFrameRecorder} is created lazily, not on the very first
 * {@link #publish(StreamId, VideoFrame)} call but once <b>two</b> things are
 * known: the frame dimensions, and the source's actual frame-arrival cadence.
 * The first {@value #CADENCE_MEASUREMENT_FRAMES} published frames are
 * consumed purely to measure that cadence (their {@code capturedAt} deltas —
 * see {@link StreamState#recordMeasurementSample}) and are themselves dropped,
 * not encoded (sub-second viewer impact, consistent with this port's
 * latest-wins contract); the recorder is then started with {@code
 * setFrameRate} and GOP size derived from the *measured* rate, not a fixed
 * assumption — see {@link StreamState} javadoc for why a fixed 15fps
 * assumption caused published streams to play in slow motion for any faster
 * source. {@link #streamEnded(StreamId)} releases the recorder and forgets
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

    /**
     * Fallback frame rate used only when the source's measured cadence is
     * degenerate (see {@link StreamState#recordMeasurementSample}) — e.g. all
     * observed {@code capturedAt} deltas are zero/identical, as a naive test
     * double might produce. A real source's frame rate is always measured;
     * this is not the assumption it used to be.
     */
    static final double DEFAULT_FRAME_RATE_FPS = 15.0;
    private static final int GOP_SECONDS = 2;
    /** Number of frames whose {@code capturedAt} deltas are sampled to measure a stream's source cadence before its recorder starts. */
    static final int CADENCE_MEASUREMENT_FRAMES = 5;
    /** Sanity clamp bounds for the measured source frame rate handed to {@code setFrameRate}. */
    static final double MIN_MEASURED_FRAME_RATE_FPS = 1.0;
    static final double MAX_MEASURED_FRAME_RATE_FPS = 120.0;
    /** Post-start drift thresholds: sustained ratio of actual-vs-measured cadence outside {@code [LOW, HIGH]} triggers one INFO log. */
    static final double DRIFT_RATIO_HIGH = 1.5;
    static final double DRIFT_RATIO_LOW = 1.0 / DRIFT_RATIO_HIGH;
    /** Smoothing factor for the post-start actual-cadence EWMA used for drift detection; same shape as {@code StreamPipeline}'s measured-fps EWMA. */
    static final double DRIFT_EWMA_ALPHA = 0.2;
    /** How long the drift ratio must stay outside bounds, continuously, before it is logged (not a single blip). */
    static final Duration SUSTAINED_DRIFT_WINDOW = Duration.ofSeconds(2);
    /** Bounds the underlying TCP connect/I/O for the RTSP push, in microseconds, so a dead mediamtx can't hang a publish call. */
    private static final String CONNECT_TIMEOUT_MICROS = "5000000";
    /**
     * x264 rate control is CRF (constant quality), not bitrate-targeted:
     * without an explicit target, {@link FFmpegFrameRecorder} falls back to
     * its ~400 kbps default — thumbnail-grade for 720p, which crushed both
     * the video and the burned-in detection boxes into macroblocks
     * (observed live). CRF keeps quality constant regardless of resolution;
     * 21 is visually clean for surveillance-style footage.
     */
    static final String X264_CRF = "21";
    /** VBV cap so a busy scene can't flood the network: CRF decides quality, this bounds the worst-case bitrate. */
    static final String X264_MAXRATE_BITS_PER_SECOND = "6000000";
    /** VBV buffer, conventionally 2× maxrate; with {@code tune=zerolatency} x264 still honors the cap per-frame. */
    static final String X264_BUFSIZE_BITS = "12000000";
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
     * AV_LOG_ERROR} so recorder start/stop no longer dumps INFO-level
     * banners to stdout/stderr -- only warnings and errors from the native
     * layer still print. Safe to call repeatedly (e.g. once per constructed
     * instance, including across many instances in a single test run): the
     * guard makes every call after the first a no-op.
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

    /**
     * Before the recorder exists, frames are fed to the pre-start cadence
     * measurement and dropped (see {@link StreamState#recordMeasurementSample});
     * once that completes, the current frame's dimensions are used to start
     * the recorder at the measured rate and the frame is written normally.
     * After the recorder exists, every frame is checked for sustained drift
     * away from the rate the recorder was configured with (see {@link
     * StreamState#observeSustainedDrift}) and then written.
     */
    private void doPublish(StreamId id, VideoFrame videoFrame, StreamState state) throws Exception {
        if (state.recorder == null) {
            if (!state.recordMeasurementSample(videoFrame.capturedAt())) {
                return; // still measuring source cadence; frame intentionally dropped, not published
            }
            Frame frame = FrameConverter.toFrame(videoFrame);
            state.recorder = startRecorder(id, frame.imageWidth, frame.imageHeight, state.measuredFrameRateFps());
            writeFrame(state, videoFrame, frame);
            return;
        }

        if (state.observeSustainedDrift(videoFrame.capturedAt())) {
            LOG.log(System.Logger.Level.INFO, () -> "Stream " + id.value() + " source frame rate has drifted "
                    + "sustainedly away from the " + state.measuredFrameRateFps() + " fps measured at stream start; "
                    + "the encoder is not restarted mid-stream (known limitation, see MODULE.md), so playback "
                    + "speed may be off until the stream is restarted");
        }
        writeFrame(state, videoFrame, FrameConverter.toFrame(videoFrame));
    }

    private static void writeFrame(StreamState state, VideoFrame videoFrame, Frame frame) throws Exception {
        state.recorder.setTimestamp(state.nextTimestampMicros(videoFrame.capturedAt(), state.measuredFrameRateFps()));
        state.recorder.record(frame);
    }

    private FFmpegFrameRecorder startRecorder(StreamId id, int width, int height, double frameRateFps) throws Exception {
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(pushUrl(id), width, height);
        configureRecorder(recorder, frameRateFps);
        try {
            recorder.start();
        } catch (Exception e) {
            releaseQuietly(recorder);
            throw e;
        }
        return recorder;
    }

    /**
     * Applies this class's fixed recorder configuration (format/codec/options)
     * plus the per-stream {@code frameRateFps} — the measured source cadence,
     * see {@link StreamState} — and its derived GOP size. Split out of {@link
     * #startRecorder} as a package-private test seam: {@link
     * FFmpegFrameRecorder#start()} is what actually touches the network, and
     * this method deliberately doesn't call it, so a test can assert the
     * exact frame rate/GOP handed to a real recorder without needing a live
     * connection.
     */
    static void configureRecorder(FFmpegFrameRecorder recorder, double frameRateFps) {
        recorder.setFormat("rtsp");
        recorder.setOption("rtsp_transport", "tcp");
        recorder.setOption("timeout", CONNECT_TIMEOUT_MICROS);
        recorder.setVideoCodec(AV_CODEC_ID_H264);
        recorder.setVideoCodecName("libx264");
        // veryfast, not ultrafast: at CRF rate control the preset trades CPU
        // for compression efficiency, and ultrafast needs roughly double the
        // bits for the same quality; veryfast is still comfortably real-time
        // for a handful of 720p streams on CPU.
        recorder.setVideoOption("preset", "veryfast");
        recorder.setVideoOption("tune", "zerolatency");
        recorder.setVideoOption("crf", X264_CRF);
        recorder.setVideoOption("maxrate", X264_MAXRATE_BITS_PER_SECOND);
        recorder.setVideoOption("bufsize", X264_BUFSIZE_BITS);
        recorder.setFrameRate(frameRateFps);
        recorder.setGopSize((int) Math.round(frameRateFps * GOP_SECONDS));
        recorder.setPixelFormat(AV_PIX_FMT_YUV420P);
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

    /**
     * Mutable bookkeeping for one active stream. Not thread-safe by design;
     * see class javadoc.
     *
     * <p>Package-private (not {@code private}) so unit tests can exercise the
     * cadence measurement and PTS quantization directly, without a real
     * {@link FFmpegFrameRecorder} connection.
     */
    static final class StreamState {

        volatile FFmpegFrameRecorder recorder;

        private boolean outage;
        private long backoffMs = INITIAL_BACKOFF_MS;
        private long nextRetryAtEpochMs = 0L;

        private Instant firstCapturedAt;
        private long lastFrameNumber = -1L;

        // -- pre-start cadence measurement ------------------------------------
        // Was: every recorder was started at a fixed DEFAULT_FRAME_RATE_FPS
        // (15.0) regardless of the source's actual rate, and every timestamp
        // was quantized onto that fixed 15fps grid (see nextTimestampMicros).
        // A 30fps source got every frame bumped to the next 15fps slot, so
        // the published timeline advanced at half wall-clock speed -- 2x slow
        // motion. Fix: measure the source's actual arrival cadence from the
        // first CADENCE_MEASUREMENT_FRAMES frames (dropped, not encoded, so
        // there is nothing to quantize onto the wrong grid yet), then start
        // the recorder -- and quantize every subsequent timestamp -- onto
        // that *measured* grid instead of a fixed assumption.
        private Instant lastMeasurementCapturedAt;
        private final long[] measurementDeltasMicros = new long[CADENCE_MEASUREMENT_FRAMES - 1];
        private int measurementDeltaCount = 0;
        private boolean measurementComplete = false;
        private double measuredFrameRateFps = DEFAULT_FRAME_RATE_FPS;

        // -- post-start drift detection ----------------------------------------
        private Instant lastDriftCapturedAt;
        private double driftEwmaFps = -1.0;
        private Instant driftStartedAt;
        private boolean driftAlreadyLogged = false;

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

        /**
         * Feeds one frame's {@code capturedAt} into the pre-start cadence
         * measurement. Must be called once per frame, in arrival order, while
         * {@link #recorder} is {@code null}.
         *
         * @return {@code true} once {@value #CADENCE_MEASUREMENT_FRAMES}
         *         frames have been observed and {@link #measuredFrameRateFps()}
         *         is ready to read (the caller may now start the recorder and
         *         write <b>this same</b> frame); {@code false} while still
         *         measuring, meaning the caller must drop this frame.
         *         Reconnect note: if a measurement already completed once for
         *         this stream (a recorder that later failed and was nulled
         *         out by {@code onPublishFailed}), this returns {@code true}
         *         immediately without re-measuring — the previously measured
         *         rate is reused so a reconnect doesn't cost another burst of
         *         dropped frames (mirrors {@link #firstCapturedAt}/{@link
         *         #lastFrameNumber} never resetting on reconnect either).
         */
        boolean recordMeasurementSample(Instant capturedAt) {
            if (measurementComplete) {
                return true;
            }
            if (lastMeasurementCapturedAt != null && measurementDeltaCount < measurementDeltasMicros.length) {
                long deltaMicros = Duration.between(lastMeasurementCapturedAt, capturedAt).toNanos() / 1000L;
                measurementDeltasMicros[measurementDeltaCount++] = deltaMicros;
            }
            lastMeasurementCapturedAt = capturedAt;
            if (measurementDeltaCount < measurementDeltasMicros.length) {
                return false;
            }
            measuredFrameRateFps = computeMeasuredFrameRateFps();
            measurementComplete = true;
            return true;
        }

        /** @return the source frame rate this stream's recorder was (or will be) configured with. */
        double measuredFrameRateFps() {
            return measuredFrameRateFps;
        }

        /**
         * Median of the {@value #CADENCE_MEASUREMENT_FRAMES}{@code -1}
         * inter-arrival deltas collected by {@link #recordMeasurementSample},
         * converted to fps and clamped to {@code [}{@link
         * #MIN_MEASURED_FRAME_RATE_FPS}{@code ,}{@link
         * #MAX_MEASURED_FRAME_RATE_FPS}{@code ]}. Median, not mean: robust
         * against a single anomalous gap (e.g. a scheduler's first tick
         * taking longer than steady state) skewing the estimate from just
         * four samples. Falls back to {@link #DEFAULT_FRAME_RATE_FPS} when
         * the median delta is non-positive — a degenerate source (all
         * {@code capturedAt} identical or non-monotonic) has no measurable
         * rate at all, so there's nothing better to fall back on than the
         * historical fixed assumption.
         */
        private double computeMeasuredFrameRateFps() {
            long[] sorted = measurementDeltasMicros.clone();
            Arrays.sort(sorted);
            int mid = sorted.length / 2;
            long medianMicros = (sorted.length % 2 == 0)
                    ? Math.round((sorted[mid - 1] + sorted[mid]) / 2.0)
                    : sorted[mid];
            if (medianMicros <= 0) {
                return DEFAULT_FRAME_RATE_FPS;
            }
            double fps = 1_000_000.0 / medianMicros;
            return clamp(fps, MIN_MEASURED_FRAME_RATE_FPS, MAX_MEASURED_FRAME_RATE_FPS);
        }

        /**
         * Tracks the stream's actual post-start arrival cadence (a separate
         * EWMA from the one-time pre-start measurement above) and reports
         * whether it has stayed outside {@code [}{@link #DRIFT_RATIO_LOW}
         * {@code ,}{@link #DRIFT_RATIO_HIGH}{@code ]} of {@link
         * #measuredFrameRateFps()}, continuously, for at least {@link
         * #SUSTAINED_DRIFT_WINDOW}. Deliberately does not react to a single
         * blip (bursty delivery is routine, see {@link #nextTimestampMicros}
         * javadoc) — only a *sustained* mismatch, meaning the encoder's fixed
         * {@code setFrameRate}/GOP no longer match the source, is worth
         * surfacing. Logs (via the caller) at most once per stream: encoder
         * restart mid-stream is out of scope (known limitation, see
         * MODULE.md), so repeating the log would add noise without giving
         * the operator anything new to act on.
         *
         * @return {@code true} the one time sustained drift is first detected
         */
        boolean observeSustainedDrift(Instant capturedAt) {
            if (driftAlreadyLogged) {
                return false;
            }
            if (lastDriftCapturedAt != null) {
                long deltaMicros = Duration.between(lastDriftCapturedAt, capturedAt).toNanos() / 1000L;
                if (deltaMicros > 0) {
                    double instantaneousFps = 1_000_000.0 / deltaMicros;
                    driftEwmaFps = driftEwmaFps < 0
                            ? instantaneousFps
                            : DRIFT_EWMA_ALPHA * instantaneousFps + (1 - DRIFT_EWMA_ALPHA) * driftEwmaFps;
                }
            }
            lastDriftCapturedAt = capturedAt;

            if (driftEwmaFps < 0) {
                return false;
            }
            double ratio = driftEwmaFps / measuredFrameRateFps;
            boolean drifting = ratio > DRIFT_RATIO_HIGH || ratio < DRIFT_RATIO_LOW;
            if (!drifting) {
                driftStartedAt = null;
                return false;
            }
            if (driftStartedAt == null) {
                driftStartedAt = capturedAt;
                return false;
            }
            if (Duration.between(driftStartedAt, capturedAt).compareTo(SUSTAINED_DRIFT_WINDOW) >= 0) {
                driftAlreadyLogged = true;
                return true;
            }
            return false;
        }

        private static double clamp(double value, double min, double max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}

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
 * <h2>Latency measurement (docs/MVP2-PLAN.md V-c)</h2>
 * Every write to the encoder measures capture→encode lag — {@code now -
 * videoFrame.capturedAt()} at the moment the frame is handed to {@code
 * FFmpegFrameRecorder.record} — into a small per-stream rolling window
 * ({@link StreamState#lagTracker}, see {@link LagTracker}). A p50/p95
 * summary is logged at {@code INFO} at most once every {@value
 * #LAG_LOG_INTERVAL_MILLIS}ms per stream; every frame's own lag is logged at
 * {@code DEBUG}. This covers only the capture→ingest→pipeline→overlay→
 * publisher-handoff span — see this module's MODULE.md for how to read it
 * together with the player's own "behind live" estimate (V-b) to see the
 * full glass-to-glass picture.
 *
 * <h2>Recording playback (docs/OPS-CORE-PLAN.md §R)</h2>
 * This class does no recording of its own: mediamtx's native recorder
 * (docker-compose.yml's {@code MTX_PATHDEFAULTS_RECORD}) segments every
 * published path to disk, and {@link #playbackUrl} is pure string formatting
 * against mediamtx's playback HTTP server's {@code /get} endpoint — the same
 * "never proxied, media-server address handed to the viewer verbatim"
 * treatment as {@link #whepUrl}, for the same reason (a byte-range-seekable
 * clip fetch is not something a simple reverse proxy adds value forwarding).
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
    /**
     * docs/MVP2-PLAN.md V-a: an HLS segment can never be shorter than the
     * keyframe interval it's cut on, so this bounds how low mediamtx's own
     * {@code hlsSegmentDuration} (compose {@code MTX_HLSSEGMENTDURATION},
     * see docker-compose.yml) can usefully go — 1s here matches mediamtx's
     * own 1s default/configured segment duration exactly. Was 2s, which
     * forced ~2s (or coarser, once VBV/network jitter is added) segments
     * regardless of mediamtx's own configuration, the single biggest
     * contributor to the "5-10s of latency" previously documented in
     * README.md's Quickstart.
     */
    private static final int GOP_SECONDS = 1;
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
    /**
     * docs/MVP2-PLAN.md V-c: ring-buffer capacity for {@link StreamState#lagTracker}.
     * 150 samples covers several seconds' worth of frames at typical 15-30fps
     * sources — enough for a stable p50/p95 read between periodic log lines
     * without holding an unbounded or needlessly large history.
     */
    static final int LAG_TRACKER_WINDOW_SIZE = 150;
    /** docs/MVP2-PLAN.md V-c: minimum wall-clock gap between a stream's periodic capture→encode lag summary logs. */
    static final long LAG_LOG_INTERVAL_MILLIS = Duration.ofSeconds(30).toMillis();
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
    /**
     * Disables x264's adaptive scene-cut keyframe insertion (default
     * threshold 40, inherited from the {@code veryfast} preset if left
     * unset). docs/MVP2-PLAN.md V-a: mediamtx cuts a new HLS segment at the
     * first keyframe at-or-after its configured {@code hlsSegmentDuration},
     * so a closed, strictly periodic GOP (one keyframe every {@link
     * #GOP_SECONDS} exactly, never early) keeps segment boundaries — and
     * therefore segment durations — predictable; scene-cut-triggered early
     * keyframes are the standard live/adaptive-streaming footgun this
     * avoids (recommended practice for HLS/DASH authoring generally, not
     * specific to this codebase).
     */
    static final String X264_SCENECUT_THRESHOLD = "0";
    private static final long INITIAL_BACKOFF_MS = 500L;
    private static final long MAX_BACKOFF_MS = 10_000L;
    private static final String HLS_PLAYLIST_SUFFIX = "/index.m3u8";
    private static final String WHEP_PATH_SUFFIX = "/whep";
    private static final String PLAYBACK_GET_PATH = "/get";
    /**
     * docs/OPS-CORE-PLAN.md §R: port the {@link #MediamtxStreamPublisher(URI, URI, URI)}
     * convenience constructor derives a playback base at, when the caller hasn't configured one
     * explicitly — matches docker-compose.yml's host-mapped playback port (mediamtx's own
     * container-side default is {@code 9996}; this stack's compose maps host {@code 19996} to it,
     * the same "renumbered to dodge collisions" convention as {@code hls-base}/{@code whep-base}'s
     * own 18888/18889).
     */
    static final int DEFAULT_PLAYBACK_PORT = 19996;

    private final URI rtspPushBase;
    private final URI hlsViewBase;
    private final URI whepViewBase;
    private final URI playbackViewBase;
    private final Map<StreamId, StreamState> streams = new ConcurrentHashMap<>();

    /**
     * @param rtspPushBase     base RTSP URL of the mediamtx sidecar to push to, e.g. {@code rtsp://localhost:8554}
     * @param hlsViewBase      base HTTP URL of mediamtx's HLS egress, e.g. {@code http://localhost:8888}
     * @param whepViewBase     base HTTP URL of mediamtx's WebRTC/WHEP egress, e.g. {@code http://localhost:8889};
     *                         unlike {@code hlsViewBase} (which {@code vision-app} typically points at an
     *                         app-relative proxy path, see {@link #viewUrl}'s javadoc), this is handed to
     *                         viewers verbatim — see {@link #whepUrl}
     * @param playbackViewBase base HTTP URL of mediamtx's playback server (docs/OPS-CORE-PLAN.md §R), e.g.
     *                         {@code http://localhost:19996}; {@code null} when this stream publisher has no
     *                         recording/playback configured, in which case {@link #playbackUrl} always returns
     *                         {@link Optional#empty()} (honest absence, not an error) — unlike {@code
     *                         rtspPushBase}/{@code hlsViewBase}/{@code whepViewBase}, this one is genuinely
     *                         optional. Never proxied, for the same reason as {@code whepViewBase}: handed to
     *                         the viewer verbatim.
     */
    public MediamtxStreamPublisher(URI rtspPushBase, URI hlsViewBase, URI whepViewBase, URI playbackViewBase) {
        ensureQuietLogging();
        this.rtspPushBase = Objects.requireNonNull(rtspPushBase, "rtspPushBase must not be null");
        this.hlsViewBase = Objects.requireNonNull(hlsViewBase, "hlsViewBase must not be null");
        this.whepViewBase = Objects.requireNonNull(whepViewBase, "whepViewBase must not be null");
        this.playbackViewBase = playbackViewBase;
    }

    /**
     * Convenience overload for callers that don't configure a playback base explicitly. As of
     * docs/OPS-CORE-PLAN.md R-a, that's {@code vision-app}'s {@code WiringConfiguration} — this
     * task's file scope is adapter-publish-hls + docker-compose.yml only, so wiring an explicit
     * {@code vision.publish.mediamtx.playback-base} property through {@code
     * VisionPublishProperties}/{@code WiringConfiguration} is a follow-up (see this module's
     * MODULE.md), not done here. This overload derives a best-effort playback base instead of
     * leaving recording unreachable in the meantime: {@code whepViewBase}'s own host at {@value
     * #DEFAULT_PLAYBACK_PORT} (matching docker-compose.yml's host-mapped playback port) —
     * {@code whepViewBase} is the closest existing analog (also never proxied, also handed to the
     * browser verbatim, see the 4-arg constructor's javadoc), so the same host is a reasonable
     * inference for a same-stack mediamtx. Falls back to no playback configured (the 4-arg
     * constructor's {@code null}) if {@code whepViewBase} has no host component to copy — this
     * never throws on a bad guess, since an unconfigured playback base is honest absence, not a
     * fatal error (the 4-arg constructor's own null-checks still apply to the other three bases).
     *
     * <p>Deliberately an overload rather than updating every call site (this module's own usual
     * convention when new-but-always-available config is added, see {@code whepViewBase}'s own
     * history in this class's MODULE.md) — {@code vision-app}, the one production call site, is
     * out of this task's scope to edit.
     */
    public MediamtxStreamPublisher(URI rtspPushBase, URI hlsViewBase, URI whepViewBase) {
        this(rtspPushBase, hlsViewBase, whepViewBase, derivePlaybackViewBase(whepViewBase));
    }

    /**
     * @return {@code scheme://host:}{@value #DEFAULT_PLAYBACK_PORT} copied from {@code
     *         whepViewBase}, or {@code null} if {@code whepViewBase} is {@code null} or has no
     *         host component (e.g. malformed input, left for the 4-arg constructor's own
     *         null-check to reject) — never throws.
     */
    private static URI derivePlaybackViewBase(URI whepViewBase) {
        if (whepViewBase == null || whepViewBase.getHost() == null) {
            return null;
        }
        String scheme = whepViewBase.getScheme() != null ? whepViewBase.getScheme() : "http";
        return URI.create(scheme + "://" + whepViewBase.getHost() + ":" + DEFAULT_PLAYBACK_PORT);
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

    /**
     * mediamtx serves WHEP for every published path with no extra
     * configuration, at {@code {whepViewBase}/{streamId}/whep} — mirrors
     * {@link #viewUrl}'s formatting (same trailing-slash tolerance) but,
     * per this port's {@code whepUrl} contract, {@code whepViewBase} is
     * never an app-relative proxy path: it is handed to the viewer verbatim,
     * since a WHEP session is a POST/SDP exchange plus ICE, not a byte
     * stream a reverse proxy can forward transparently the way {@code
     * HlsProxyController} (vision-api) does for HLS segments.
     */
    @Override
    public Optional<URI> whepUrl(StreamId id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.of(URI.create(withoutTrailingSlash(whepViewBase.toString()) + "/" + id.value() + WHEP_PATH_SUFFIX));
    }

    /**
     * mediamtx's playback server serves a clip for any {@code [start, start + duration)} window
     * of a recorded path at {@code {playbackViewBase}/get?path={streamId}&start={RFC3339}
     * &duration={seconds}} — the {@code path} value is the exact same mediamtx path name {@link
     * #viewUrl}/{@link #whepUrl} already use ({@code streamId.value()}), since recording is keyed
     * on the same path every published stream already lives at. {@code start} uses {@link
     * Instant#toString()} verbatim (already RFC3339/ISO-8601 with a trailing {@code Z}, which is
     * exactly the {@code time.RFC3339} format mediamtx's own playback server parses with, verified
     * against mediamtx v1.19.3's {@code internal/playback/on_get.go}); {@code duration} is rounded
     * to the nearest whole second (mediamtx's own {@code duration} parameter accepts fractional
     * seconds too, but callers of this port only ever have second-granularity usage windows to
     * begin with — see docs/OPS-CORE-PLAN.md §R's {@code AssetUsage}-based join — so sub-second
     * precision would be false precision, not a real distinction).
     *
     * <p>Returns {@link Optional#empty()} whenever {@code playbackViewBase} is unconfigured (see
     * the constructors' javadoc) or {@code id} is {@code null} — mirrors {@link #viewUrl}/{@link
     * #whepUrl}'s {@code null}-id handling. {@code start}/{@code duration} are required inputs once
     * a playback base and stream id are present, so a {@code null} for either is a caller bug,
     * not an absence to represent — same idiom as this codebase's application layer, per CLAUDE.md.
     */
    @Override
    public Optional<URI> playbackUrl(StreamId id, Instant start, Duration duration) {
        if (playbackViewBase == null || id == null) {
            return Optional.empty();
        }
        Objects.requireNonNull(start, "start must not be null");
        Objects.requireNonNull(duration, "duration must not be null");
        long durationSeconds = Math.round(duration.toMillis() / 1000.0);
        return Optional.of(URI.create(withoutTrailingSlash(playbackViewBase.toString()) + PLAYBACK_GET_PATH
                + "?path=" + id.value() + "&start=" + start + "&duration=" + durationSeconds));
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
            writeFrame(id, state, videoFrame, frame);
            return;
        }

        if (state.observeSustainedDrift(videoFrame.capturedAt())) {
            LOG.log(System.Logger.Level.INFO, () -> "Stream " + id.value() + " source frame rate has drifted "
                    + "sustainedly away from the " + state.measuredFrameRateFps() + " fps measured at stream start; "
                    + "the encoder is not restarted mid-stream (known limitation, see MODULE.md), so playback "
                    + "speed may be off until the stream is restarted");
        }
        writeFrame(id, state, videoFrame, FrameConverter.toFrame(videoFrame));
    }

    /**
     * Writes one frame to the encoder and, immediately before doing so,
     * measures docs/MVP2-PLAN.md V-c's capture→encode lag: {@code now -
     * videoFrame.capturedAt()}, i.e. everything upstream of this handoff
     * (capture, ingest decode, {@code StreamPipeline}, overlay burn-in, and
     * this class's own measurement/backoff bookkeeping) — never anything
     * downstream (mediamtx segmenting, HLS/WHEP transport, player buffering;
     * see this module's MODULE.md for how to combine this with the player's
     * own "behind live" estimate to see the full glass-to-glass split).
     * Recording a sample is an {@code O(1)} array write ({@link
     * LagTracker#record}) — negligible per-frame overhead — and the DEBUG log
     * below only builds its message when DEBUG is actually enabled ({@link
     * System.Logger#log(System.Logger.Level, java.util.function.Supplier)}'s
     * lazy-supplier form, the same idiom this class already uses for its
     * INFO/WARNING logs).
     */
    private static void writeFrame(StreamId id, StreamState state, VideoFrame videoFrame, Frame frame) throws Exception {
        long nowEpochMs = System.currentTimeMillis();
        long lagMillis = nowEpochMs - videoFrame.capturedAt().toEpochMilli();
        state.lagTracker.record(lagMillis);
        LOG.log(System.Logger.Level.DEBUG, () -> "Stream " + id.value() + ": capture→encode lag " + lagMillis
                + "ms (frame " + videoFrame.sequence() + ")");
        if (state.shouldLogLag(nowEpochMs)) {
            LOG.log(System.Logger.Level.INFO, () -> "Stream " + id.value() + ": capture→encode lag p50/p95 ~"
                    + state.lagTracker.p50() + "/" + state.lagTracker.p95() + "ms (n=" + state.lagTracker.sampleCount() + ")");
        }

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
     *
     * <p><b>Latency audit (docs/MVP2-PLAN.md V-a):</b> {@code tune=zerolatency}
     * (verified against x264's own source) already expands to {@code
     * --bframes 0 --no-mbtree --sync-lookahead 0 --rc-lookahead 0
     * --force-cfr}, i.e. zero B-frames and zero rate-control/frame-type
     * lookahead — there is no reordering delay between a frame being
     * captured and it leaving the encoder. {@link
     * FFmpegFrameRecorder#setMaxBFrames} is set to {@code 0} anyway, purely
     * as redundant, independently-testable documentation of that fact (not
     * a behavior change — {@code tune} already forces it) in case a future
     * edit ever changes {@code tune} without re-deriving the consequence.
     * {@code maxrate}/{@code bufsize} (the CRF fix, commit a963521) are left
     * untouched: VBV bufsize bounds instantaneous bitrate *variance* for the
     * rate controller, it is not a frame-reordering/output-delay buffer —
     * with zero lookahead each frame is written essentially as soon as it's
     * encoded, so this does not regress latency.
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
        recorder.setVideoOption("sc_threshold", X264_SCENECUT_THRESHOLD);
        recorder.setMaxBFrames(0);
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

        // -- docs/MVP2-PLAN.md V-c: capture→encode lag measurement --------------
        // Package-private, not private, mirroring `recorder` above: the owning
        // MediamtxStreamPublisher.writeFrame reads/writes it directly, no
        // getter ceremony needed for a per-stream, single-writer field.
        final LagTracker lagTracker = new LagTracker(LAG_TRACKER_WINDOW_SIZE);
        private long nextLagLogAtEpochMs = 0L;

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

        /**
         * Gate for the periodic per-stream capture→encode lag summary log
         * (docs/MVP2-PLAN.md V-c): {@code true} at most once per {@value
         * MediamtxStreamPublisher#LAG_LOG_INTERVAL_MILLIS}ms of wall-clock
         * time, and never on the very first call — that call only
         * establishes the baseline, since logging immediately would report a
         * single-sample "p50/p95" before the rolling window holds anything
         * meaningful. Millisecond epoch, not {@link Instant}, matching this
         * class's own {@link #readyToRetry()}/{@link #scheduleRetry()}
         * backoff-timing idiom.
         */
        boolean shouldLogLag(long nowEpochMs) {
            if (nextLagLogAtEpochMs == 0L) {
                nextLagLogAtEpochMs = nowEpochMs + LAG_LOG_INTERVAL_MILLIS;
                return false;
            }
            if (nowEpochMs < nextLagLogAtEpochMs) {
                return false;
            }
            nextLagLogAtEpochMs = nowEpochMs + LAG_LOG_INTERVAL_MILLIS;
            return true;
        }

        private static double clamp(double value, double min, double max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}

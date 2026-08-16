package com.drones.vision.adapter.publishhls;

import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.VideoFrame;
import com.drones.vision.perception.domain.port.StreamPublisherPort;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link StreamPublisherPort} that re-encodes frames to H.264 and pushes
 * them as an RTSP stream to a <a href="https://github.com/bluenviron/mediamtx">mediamtx</a>
 * sidecar; browsers then watch mediamtx's HLS egress. See {@code docs/plans/done/PHASE1-PLAN.md}
 * §0.1/§0.3 and §3 for the design this class implements.
 *
 * <h2>Lifecycle</h2>
 * {@link #streamStarted(StreamId, Device)} only registers bookkeeping state —
 * the {@link FFmpegFrameRecorder} is created lazily, not on the very first
 * {@link #publish(StreamId, VideoFrame)} call but once <b>two</b> things are
 * known: the frame dimensions, and the source's actual frame-arrival cadence.
 * The first {@value CadenceEstimator#CADENCE_MEASUREMENT_FRAMES} published frames are
 * consumed purely to measure that cadence (their {@code capturedAt} deltas —
 * see {@link CadenceEstimator#recordMeasurementSample}) and are themselves dropped,
 * not encoded (sub-second viewer impact, consistent with this port's
 * latest-wins contract); the recorder is then started with {@code
 * setFrameRate} and GOP size derived from the *measured* rate, not a fixed
 * assumption — see {@link CadenceEstimator} javadoc for why a fixed 15fps
 * assumption caused published streams to play in slow motion for any faster
 * source. {@link #streamEnded(StreamId)} releases the recorder and forgets
 * the stream; both are idempotent.
 *
 * <h2>Resilience</h2>
 * Nothing thrown by JavaCV/FFmpeg (or by frame conversion) ever escapes
 * {@link #publish}, {@link #streamStarted}, or {@link #streamEnded}: a
 * broken or absent mediamtx must never take down the owning pipeline. While
 * a stream is broken, frames are dropped and reconnect attempts are
 * throttled with exponential backoff (see {@link PublishBackoff}); a single
 * {@code WARNING} is logged per outage (not per dropped frame).
 *
 * <h2>Threading</h2>
 * Per {@link StreamPublisherPort}'s contract, calls for a single {@code
 * streamId} are not concurrent, so per-stream state needs no internal
 * locking; different streams are tracked independently in a {@link
 * ConcurrentHashMap} since their pipelines run on different threads.
 *
 * <h2>Latency measurement (docs/plans/done/MVP2-PLAN.md V-c)</h2>
 * Every write to the encoder measures capture→encode lag — {@code now -
 * videoFrame.capturedAt()} at the moment the frame is handed to {@code
 * FFmpegFrameRecorder.record} — into a small per-stream rolling window
 * ({@link PublishDiagnostics#lagTracker}, see {@link LagTracker}). A p50/p95
 * summary is logged at {@code INFO} at most once every {@value
 * PublishDiagnostics#LAG_LOG_INTERVAL_MILLIS}ms per stream; every frame's own lag is logged at
 * {@code DEBUG}. This covers only the capture→ingest→pipeline→overlay→
 * publisher-handoff span — see this module's MODULE.md for how to read it
 * together with the player's own "behind live" estimate (V-b) to see the
 * full glass-to-glass picture.
 *
 * <h2>Recording playback (docs/plans/done/OPS-CORE-PLAN.md §R)</h2>
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

    private final URI rtspPushBase;
    private final URI hlsViewBase;
    private final URI whepViewBase;
    private final URI playbackViewBase;
    private final PublishSettings settings;
    private final Map<StreamId, StreamState> streams = new ConcurrentHashMap<>();

    /**
     * @param rtspPushBase     base RTSP URL of the mediamtx sidecar to push to, e.g. {@code rtsp://localhost:8554}
     * @param hlsViewBase      base HTTP URL of mediamtx's HLS egress, e.g. {@code http://localhost:8888}
     * @param whepViewBase     base HTTP URL of mediamtx's WebRTC/WHEP egress, e.g. {@code http://localhost:8889};
     *                         unlike {@code hlsViewBase} (which {@code vision-app} typically points at an
     *                         app-relative proxy path, see {@link #viewUrl}'s javadoc), this is handed to
     *                         viewers verbatim — see {@link #whepUrl}
     * @param playbackViewBase base HTTP URL of mediamtx's playback server (docs/plans/done/OPS-CORE-PLAN.md §R), e.g.
     *                         {@code http://localhost:19996}; {@code null} when this stream publisher has no
     *                         recording/playback configured, in which case {@link #playbackUrl} always returns
     *                         {@link Optional#empty()} (honest absence, not an error) — unlike {@code
     *                         rtspPushBase}/{@code hlsViewBase}/{@code whepViewBase}, this one is genuinely
     *                         optional. Never proxied, for the same reason as {@code whepViewBase}: handed to
     *                         the viewer verbatim.
     */
    public MediamtxStreamPublisher(URI rtspPushBase, URI hlsViewBase, URI whepViewBase, URI playbackViewBase) {
        this(rtspPushBase, hlsViewBase, whepViewBase, playbackViewBase, PublishSettings.defaults());
    }

    /**
     * @param settings encoder/resilience/cadence tunables (docs/plans/active/LAYERING-REFACTOR-PLAN.md wave F3,
     *                 {@code vision.publish.encoder.*}/{@code .resilience.*}/{@code .cadence.*}) —
     *                 threaded into every {@link StreamState} this instance creates.
     */
    public MediamtxStreamPublisher(URI rtspPushBase, URI hlsViewBase, URI whepViewBase, URI playbackViewBase,
                                    PublishSettings settings) {
        ensureQuietLogging();
        this.rtspPushBase = Objects.requireNonNull(rtspPushBase, "rtspPushBase must not be null");
        this.hlsViewBase = Objects.requireNonNull(hlsViewBase, "hlsViewBase must not be null");
        this.whepViewBase = Objects.requireNonNull(whepViewBase, "whepViewBase must not be null");
        this.playbackViewBase = playbackViewBase;
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
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
        StreamState previous = streams.put(id, new StreamState(settings));
        releaseQuietly(previous == null ? null : previous.recorder);
        LOG.log(System.Logger.Level.INFO, () -> "Publishing stream " + id.value()
                + (device != null ? " (" + device.name() + ")" : "") + " to " + pushUrl(id));
    }

    @Override
    public void publish(StreamId id, VideoFrame frame) {
        if (id == null || frame == null) {
            return;
        }
        StreamState state = streams.computeIfAbsent(id, unused -> new StreamState(settings));
        if (state.recorder == null && !state.backoff.readyToRetry()) {
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
        return Optional.of(URI.create(MediamtxUrls.viewUrl(hlsViewBase, id)));
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
        return Optional.of(URI.create(MediamtxUrls.whepUrl(whepViewBase, id)));
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
     * begin with — see docs/plans/done/OPS-CORE-PLAN.md §R's {@code AssetUsage}-based join — so sub-second
     * precision would be false precision, not a real distinction).
     *
     * <p>Returns {@link Optional#empty()} whenever {@code playbackViewBase} is unconfigured (see
     * the constructor's javadoc) or {@code id} is {@code null} — mirrors {@link #viewUrl}/{@link
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
        return Optional.of(URI.create(
                MediamtxPlaybackUrls.getUrl(playbackViewBase, id.value().toString(), start, durationSeconds)));
    }

    /**
     * Snapshot of this publisher's currently-tracked streams — {@code video-publish}'s {@code
     * SubsystemStatusPort} plumbing (docs/plans/active/SYSTEM-STATUS-PLAN.md §4.2), read by {@code
     * PublishStatusProvider} (same package). {@code total} is every stream between {@link
     * #streamStarted} and {@link #streamEnded}; {@code inOutage} names each one currently
     * mid-{@link PublishBackoff} outage (dropping frames, backing off reconnects) rather than
     * actually pushing to mediamtx — the plan calls for naming the affected stream(s), not just a
     * count.
     */
    public record PublishSnapshot(int total, List<StreamId> inOutage) {
    }

    /** See {@link PublishSnapshot}. */
    public PublishSnapshot streamsInOutage() {
        int total = streams.size();
        List<StreamId> inOutage = streams.entrySet().stream()
                .filter(entry -> entry.getValue().backoff.inOutage())
                .map(Map.Entry::getKey)
                .toList();
        return new PublishSnapshot(total, inOutage);
    }

    // -- publish machinery --------------------------------------------------

    /**
     * Before the recorder exists, frames are fed to the pre-start cadence
     * measurement and dropped (see {@link CadenceEstimator#recordMeasurementSample});
     * once that completes, the current frame's dimensions are used to start
     * the recorder at the measured rate and the frame is written normally.
     * After the recorder exists, every frame is checked for sustained drift
     * away from the rate the recorder was configured with (see {@link
     * CadenceEstimator#observeSustainedDrift}) and then written.
     */
    private void doPublish(StreamId id, VideoFrame videoFrame, StreamState state) throws Exception {
        if (state.recorder == null) {
            if (!state.cadence.recordMeasurementSample(videoFrame.capturedAt())) {
                return; // still measuring source cadence; frame intentionally dropped, not published
            }
            Frame frame = FrameConverter.toFrame(videoFrame);
            state.recorder = startRecorder(id, frame.imageWidth, frame.imageHeight, state.cadence.measuredFrameRateFps());
            writeFrame(id, state, videoFrame, frame);
            return;
        }

        if (state.cadence.observeSustainedDrift(videoFrame.capturedAt())) {
            LOG.log(System.Logger.Level.INFO, () -> "Stream " + id.value() + " source frame rate has drifted "
                    + "sustainedly away from the " + state.cadence.measuredFrameRateFps() + " fps measured at stream start; "
                    + "the encoder is not restarted mid-stream (known limitation, see MODULE.md), so playback "
                    + "speed may be off until the stream is restarted");
        }
        writeFrame(id, state, videoFrame, FrameConverter.toFrame(videoFrame));
    }

    /**
     * Writes one frame to the encoder and, immediately before doing so,
     * measures docs/plans/done/MVP2-PLAN.md V-c's capture→encode lag: {@code now -
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
        state.diagnostics.lagTracker.record(lagMillis);
        LOG.log(System.Logger.Level.DEBUG, () -> "Stream " + id.value() + ": capture→encode lag " + lagMillis
                + "ms (frame " + videoFrame.sequence() + ")");
        if (state.diagnostics.shouldLogLag(nowEpochMs)) {
            LOG.log(System.Logger.Level.INFO, () -> "Stream " + id.value() + ": capture→encode lag p50/p95 ~"
                    + state.diagnostics.lagTracker.p50() + "/" + state.diagnostics.lagTracker.p95()
                    + "ms (n=" + state.diagnostics.lagTracker.sampleCount() + ")");
        }

        state.recorder.setTimestamp(
                state.cadence.nextTimestampMicros(videoFrame.capturedAt(), state.cadence.measuredFrameRateFps()));
        state.recorder.record(frame);
    }

    private FFmpegFrameRecorder startRecorder(StreamId id, int width, int height, double frameRateFps) throws Exception {
        return H264RecorderFactory.create(pushUrl(id), width, height, frameRateFps, settings.encoder());
    }

    private String pushUrl(StreamId id) {
        return MediamtxUrls.pushUrl(rtspPushBase, id);
    }

    private void onPublishSucceeded(StreamId id, StreamState state) {
        if (state.backoff.endOutage()) {
            LOG.log(System.Logger.Level.INFO, () -> "Resumed publishing stream " + id.value() + " to mediamtx");
        }
    }

    private void onPublishFailed(StreamId id, StreamState state, Exception e) {
        releaseQuietly(state.recorder);
        state.recorder = null;
        if (state.backoff.beginOutage()) {
            LOG.log(System.Logger.Level.WARNING,
                    "Failed to publish stream " + id.value() + " to mediamtx at " + pushUrl(id)
                            + "; will keep retrying with backoff and drop frames until it recovers", e);
        }
        state.backoff.scheduleRetry();
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

    // -- per-stream state -----------------------------------------------------

    /**
     * Per-stream bookkeeping: the lazily-created recorder itself, plus three collaborators each
     * owning one concern that used to live inline here — {@link PublishBackoff} (reconnect
     * throttling), {@link CadenceEstimator} (cadence measurement, PTS quantization, drift
     * detection), and {@link PublishDiagnostics} (docs/plans/done/MVP2-PLAN.md V-c lag tracking). Not
     * thread-safe by design; see class javadoc, "Threading".
     *
     * <p>Package-private (not {@code private}) so unit tests can exercise the collaborators
     * directly, without a real {@link FFmpegFrameRecorder} connection.
     */
    static final class StreamState {

        volatile FFmpegFrameRecorder recorder;

        final PublishBackoff backoff;
        final CadenceEstimator cadence;
        final PublishDiagnostics diagnostics = new PublishDiagnostics();

        StreamState(PublishSettings settings) {
            this.backoff = new PublishBackoff(settings.resilience());
            this.cadence = new CadenceEstimator(settings.cadence());
        }
    }
}

package com.drones.vision.api.controller;

import com.drones.vision.api.dto.ActiveStreamResponse;
import com.drones.vision.api.dto.DetectionResultResponse;
import com.drones.vision.api.dto.StartStreamRequest;
import com.drones.vision.api.dto.StartStreamResponse;
import com.drones.vision.api.dto.DetectionRateResponse;
import com.drones.vision.api.dto.PipelineLatencyResponse;
import com.drones.vision.api.dto.StreamConfigResponse;
import com.drones.vision.api.dto.StreamTracksResponse;
import com.drones.vision.api.dto.TrackResponse;
import com.drones.vision.api.dto.TrackStatsResponse;
import com.drones.vision.api.dto.UpdateStreamConfigRequest;
import com.drones.vision.api.dto.UpdateStreamConfigResponse;
import com.drones.vision.api.security.StreamAccess;
import com.drones.vision.api.support.StreamDetectionSupport;
import com.drones.vision.perception.application.pipeline.TrackingStats;
import com.drones.vision.perception.application.stream.StreamService;
import com.drones.vision.perception.application.stream.TrackingConfigPatch;
import com.drones.vision.perception.application.stream.PipelineConfigPatch;
import com.drones.vision.perception.application.stream.UpdateOutcome;
import com.drones.vision.perception.domain.model.DetectionQuery;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.perception.domain.model.DetectionState;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.VideoFrame;
import com.drones.vision.perception.domain.port.DetectionRepositoryPort;
import com.drones.vision.perception.domain.port.StreamPublisherPort;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import com.drones.vision.api.support.SnapshotJpegEncoder;

/**
 * Driving REST adapter for starting, stopping, and listing stream pipelines.
 *
 * <p>Constructor-injected with {@link StreamService} plus {@link
 * StreamPublisherPort}, used read-only here to resolve {@code viewUrl}/{@code whepUrl} for
 * whichever publisher is currently wired in ({@code vision-app} decides
 * which implementation that is). Per the hexagonal dependency rule
 * (ARCHITECTURE.md §2, enforced by ArchUnit), this module depends only on
 * {@code vision-domain} and {@code vision-application} — never on an
 * adapter.
 *
 * <p>No acting user is threaded through here: a stream is transient plumbing rather than a
 * change to the fleet, so nothing on this path is audited against a principal. Asset-level
 * streaming, which is, lives on {@link AssetController}. {@link #updateConfig} (docs/plans/done/CV-CONTROL-PLAN.md
 * §3) follows the same stance — a live config tweak is not an audited fleet change either.
 *
 * <p>Also exposes recent detections read-only over {@link DetectionRepositoryPort} (docs/plans/done/MVP1-PLAN.md
 * §C8 bullet 3) — the same precedent {@link AssetController} already sets for {@link
 * com.drones.vision.flight.domain.port.TelemetryRepositoryPort}: no driving use-case exists for "read
 * a stream's recent detections", so this controller reads the driven port directly instead.
 *
 * <p>{@link #snapshot} (docs/plans/done/MVP3-PLAN.md C-a) is the one binary (non-JSON) response in this
 * controller — a JPEG thumbnail of a running stream's latest published frame, cheap enough for a
 * manager dashboard to poll per-visible-tile.
 *
 * <h2>Authority (docs/plans/active/LIVE-SCOPE-PLAN.md §2, W2)</h2>
 * "No acting user is threaded through here" (above) was true for attribution, but not for
 * authorization — before this wave, none of these eight handlers checked whether the caller's
 * {@link com.drones.vision.platform.VisibilityScope} reached the stream's owning asset at all, so a
 * PILOT scoped to two assets could list, read, snapshot and reconfigure any stream in the fleet.
 * {@link #list} now filters to visible streams via {@link StreamAccess#filterVisible}; every other
 * handler calls {@link StreamAccess#requireVisible(StreamId)} (or, for {@link #start}, the {@code
 * DeviceId} overload, since no stream exists yet) — an invisible target 404s exactly like an
 * unknown id, the same "existence hides itself" idiom {@link AssetController#details} uses. See
 * {@link StreamAccess}'s own javadoc for why this is one check reused for both reads and writes,
 * not a read/write split. This pushes the constructor one parameter past this codebase's
 * five-parameter target (.claude/skills/java-clean-code/SKILL.md §3): none of the other five
 * collaborators can absorb {@link StreamAccess} without conflating an authorization concern into an
 * unrelated one (URL resolution, JPEG encoding, detection-demand bookkeeping), so the sixth
 * parameter is accepted deliberately rather than forced into a false merge.
 */
@RestController
public class StreamController {

    private static final System.Logger LOG = System.getLogger(StreamController.class.getName());

    /** Default {@code limit} for {@link #detections} when the query parameter is absent. */
    private static final int DEFAULT_DETECTIONS_LIMIT = 50;

    private final StreamService streamService;
    private final StreamPublisherPort streamPublisherPort;
    private final DetectionRepositoryPort detectionRepositoryPort;
    /**
     * Constructor-injected (docs/plans/active/LAYERING-REFACTOR-PLAN.md wave D) — {@code vision-app} now supplies
     * this as a real bean, mapped from its own Spring {@code VisionApiProperties} record, replacing
     * this field's previous self-constructed {@code VisionApiProperties.defaults()} stopgap.
     */
    private final SnapshotJpegEncoder snapshotJpegEncoder;
    /**
     * Bundles the deployment's default {@link PipelineConfig} and the detection-demand poll-touch
     * seam (docs/plans/active/CV-DEMAND-PLAN.md &sect;3.5/&sect;3.8) behind one parameter — see
     * {@link StreamDetectionSupport}'s own javadoc for why these two single-method reads are bundled
     * rather than each taking its own constructor slot.
     */
    private final StreamDetectionSupport streamDetectionSupport;
    /** The authority seam every handler below consults — see this class's own "Authority" section. */
    private final StreamAccess streamAccess;

    public StreamController(StreamService streamService, StreamPublisherPort streamPublisherPort,
                             DetectionRepositoryPort detectionRepositoryPort,
                             SnapshotJpegEncoder snapshotJpegEncoder,
                             StreamDetectionSupport streamDetectionSupport,
                             StreamAccess streamAccess) {
        this.streamService = Objects.requireNonNull(streamService, "streamService must not be null");
        this.streamPublisherPort =
                Objects.requireNonNull(streamPublisherPort, "streamPublisherPort must not be null");
        this.detectionRepositoryPort =
                Objects.requireNonNull(detectionRepositoryPort, "detectionRepositoryPort must not be null");
        this.snapshotJpegEncoder =
                Objects.requireNonNull(snapshotJpegEncoder, "snapshotJpegEncoder must not be null");
        this.streamDetectionSupport =
                Objects.requireNonNull(streamDetectionSupport, "streamDetectionSupport must not be null");
        this.streamAccess = Objects.requireNonNull(streamAccess, "streamAccess must not be null");
    }

    /**
     * Starts a stream pipeline for the given device. The optional request
     * body's fields, if present, override the corresponding values from
     * {@link StreamDetectionSupport#defaultConfig()} — the deployment's own default, which starts
     * equal to {@link PipelineConfig#defaults()} except for {@code detectionEnabled}
     * (docs/plans/active/CV-DEMAND-PLAN.md &sect;3.7/&sect;3.8, {@code
     * vision.cv.detection-default-enabled}); everything else comes from that same default.
     *
     * <p>What the body says about {@code tracking} travels as its own patch rather than baked into
     * the config: the deployment's tracking seed ({@code vision.tracking.*}) is applied inside {@link
     * StreamService#start(DeviceId, PipelineConfig, com.drones.vision.perception.application.stream.TrackingConfigPatch)},
     * so this endpoint, asset-level start and the simulation service all seed identically
     * (docs/extracts/TRACKING-ORCHESTRATION.md §4.1).
     *
     * @param deviceId the device to stream from
     * @param request  optional overrides; {@code null}/absent means use every default
     * @return the started stream's id and (if available) its viewer URLs
     * @throws java.util.NoSuchElementException if the caller's scope may not reach this device's
     *                                            asset (docs/plans/active/LIVE-SCOPE-PLAN.md §2, W2)
     *                                            — the same 404 an unknown device already produces
     */
    @PostMapping("/api/devices/{deviceId}/stream")
    @ResponseStatus(HttpStatus.CREATED)
    public StartStreamResponse start(@PathVariable String deviceId,
                                      @RequestBody(required = false) StartStreamRequest request) {
        DeviceId device = DeviceId.of(deviceId);
        StartStreamRequest body = request == null ? StartStreamRequest.EMPTY : request;
        // Body validation (a malformed override is a 400) runs before the scope guard's 404, so a
        // bad request never depends on the caller's scope -- the same ordering AssetController's
        // requireManageable documents.
        PipelineConfig config = body.mergeOnto(streamDetectionSupport.defaultConfig());
        TrackingConfigPatch tracking = body.trackingPatch();
        streamAccess.requireVisible(device);
        StreamId streamId = streamService.start(device, config, tracking);
        StartStreamResponse response = new StartStreamResponse(streamId.value().toString(), viewUrl(streamId),
                whepUrl(streamId));
        LOG.log(System.Logger.Level.INFO, () -> "Started stream " + response.streamId() + " for device " + deviceId
                + " viewUrl=" + response.viewUrl() + " whepUrl=" + response.whepUrl());
        return response;
    }

    /**
     * Lists streams currently active on this instance.
     *
     * <p>Carries each stream's <b>state</b> and its <b>detection intent</b> alongside the viewer URLs
     * (docs/plans/active/STREAM-STATE-PLAN.md &sect;2.5). Both are new, and both exist because this is
     * the poll a fleet-wide client already runs: without them a client had to infer liveness from
     * mere presence in this list, and had no way at all to learn whether detection was on for a
     * stream — so it rendered its own local guess instead.
     *
     * @return the active streams the caller's scope may reach, each with its viewer URLs if
     *         available, its video-flow state, the operator's detect-on/off intent, and which CV
     *         gate currently explains its boxes
     */
    @GetMapping("/api/streams")
    public List<ActiveStreamResponse> list() {
        // Filtered, not all-or-nothing 403'd (docs/plans/active/LIVE-SCOPE-PLAN.md §2.2) -- a PILOT
        // still sees their own assigned assets' streams, just not the rest of the fleet's.
        return streamAccess.filterVisible(streamService.streams()).stream()
                .map(s -> ActiveStreamResponse.from(s, viewUrl(s.streamId()), whepUrl(s.streamId()),
                        streamService.detectionState(s.streamId()).orElse(null)))
                .toList();
    }

    /**
     * Stops a running stream. Stopping an unknown or already-stopped stream
     * is a no-op (per {@link StreamService#stop(StreamId)}'s contract) and
     * still returns 204 — there is no "stream not found" error case.
     *
     * @param streamId the stream to stop
     * @throws java.util.NoSuchElementException if {@code streamId} currently names a running stream
     *                                            whose asset the caller's scope may not reach
     *                                            (docs/plans/active/LIVE-SCOPE-PLAN.md §2, W2) — an
     *                                            id that does not currently resolve to a running
     *                                            stream keeps its pre-existing no-op/204 behavior
     */
    @DeleteMapping("/api/streams/{streamId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stop(@PathVariable String streamId) {
        StreamId id = StreamId.of(streamId);
        streamAccess.requireVisible(id);
        streamService.stop(id);
        LOG.log(System.Logger.Level.INFO, () -> "Stopped stream " + streamId);
    }

    /**
     * Live-updates a running stream's detection config (docs/plans/done/CV-CONTROL-PLAN.md §3's frozen wire
     * contract) — a partial patch: only fields present in the body change, everything else is left
     * as-is. Confidence threshold, inference fps, label filter, and detection on/off apply instantly
     * with no video interruption; a present {@code model} that differs from the stream's currently
     * running one briefly re-arms detection instead (video stays untouched either way) — the caller
     * learns this happened via {@link UpdateStreamConfigResponse#modelReArmed()}.
     *
     * <p>A {@code tracking} object (docs/plans/done/TRACKING-PLAN.md §4.D) is the third, independent family of
     * change, and it is a <b>hot knob like any other</b>: mode, engine, cadences and the target lock
     * all apply live and <b>never re-arm the detector</b>. The caller learns whether it actually
     * changed anything via {@link UpdateStreamConfigResponse#trackingChanged()}. Click-to-follow is
     * exactly this call ({@code {"tracking":{"mode":"FOLLOW","lock":{"trackId":7}}}}) — there is no
     * separate endpoint for it, and a client never sends a {@code lockSeq}.
     *
     * <p>Every field inside {@code tracking} is independently optional and folds <b>per field</b>
     * onto the running stream's own configuration in the application layer. This controller passes
     * the body through and reconstructs nothing: the running configuration is not this module's
     * state, and the only readback it ever had ({@code StreamService#trackingStats}) could restore
     * the mode and engine but none of the five cadences — which is precisely how sending {@code
     * verifyEveryMillis} and then {@code followFps} used to revert the first.
     *
     * @param streamId the running stream to update, as a canonical UUID string
     * @param request  the knobs to change; the whole body may be absent (a no-op patch)
     * @return the updated stream's id, whether the model was re-armed, and whether tracking changed
     * @throws java.util.NoSuchElementException if {@code streamId} is unknown or not running on this
     *                                            instance (→404), or is running but the caller's
     *                                            scope may not reach its asset
     *                                            (docs/plans/active/LIVE-SCOPE-PLAN.md §2, W2 — same
     *                                            404, existence hidden either way)
     * @throws IllegalArgumentException          if the merged config fails {@link PipelineConfig}'s
     *                                            own validation, e.g. confidence outside [0,1], an
     *                                            unknown tracking {@code mode}, or a {@code lock}
     *                                            that is not exactly one of its three forms (→400)
     */
    @PatchMapping("/api/streams/{streamId}/config")
    public UpdateStreamConfigResponse updateConfig(@PathVariable String streamId,
                                                     @RequestBody(required = false) UpdateStreamConfigRequest request) {
        UpdateStreamConfigRequest body = request != null ? request : UpdateStreamConfigRequest.EMPTY;
        StreamId id = StreamId.of(streamId);
        // Patch validation (a malformed value is a 400) runs before the scope guard's 404 -- same
        // ordering as #start.
        PipelineConfigPatch patch = body.toPatch();
        streamAccess.requireVisible(id);
        UpdateOutcome outcome = streamService.updateConfig(id, patch);
        return new UpdateStreamConfigResponse(id.value().toString(), outcome.modelReArmed(),
                outcome.trackingChanged());
    }

    /**
     * A running stream's effective configuration (docs/plans/active/STREAM-STATE-PLAN.md &sect;2.5) —
     * the read half {@link #updateConfig} never had.
     *
     * <p><b>Unlike {@link #tracks}, this is not forgiving.</b> An unknown or stopped stream is a 404,
     * not a 200 carrying defaults. The forgiving idiom is right for a *polled* read model, where a
     * client wants one code path and an empty answer is honest; it is wrong here, because a
     * plausible-looking default configuration for a stream that does not exist is precisely the
     * fiction this endpoint was added to abolish — a client would render it as the running state and
     * never know the difference.
     *
     * @param streamId the running stream to inspect, as a canonical UUID string
     * @return the configuration the pipeline is running right now
     * @throws java.util.NoSuchElementException if {@code streamId} is unknown or not running on this
     *                                            instance (&rarr;404), or is running but the
     *                                            caller's scope may not reach its asset
     *                                            (docs/plans/active/LIVE-SCOPE-PLAN.md §2, W2 — same
     *                                            404)
     */
    @GetMapping("/api/streams/{streamId}/config")
    public StreamConfigResponse config(@PathVariable String streamId) {
        StreamId id = StreamId.of(streamId);
        streamAccess.requireVisible(id);
        return streamService.config(id)
                .map(StreamConfigResponse::from)
                .orElseThrow(() -> new NoSuchElementException("Unknown or stopped stream: " + streamId));
    }

    /**
     * A running stream's track book plus the duty-cycle counters over it (docs/plans/done/TRACKING-PLAN.md
     * §4.E's frozen wire contract) — what backs the cockpit's track list, its
     * "Following #N — release" chip, and the flow strip that puts "the detector stopped running and
     * the tracker took over" on screen instead of in {@code htop}.
     *
     * <p><b>Never errors for an unknown or stopped stream</b> — that case is a 200 with an empty
     * list, {@code lockedTrackId: 0} and no {@code stats}, the same forgiving idiom {@link
     * #detections} uses, and the reason a polling client needs one code path instead of two. Only a
     * malformed UUID, or a <em>currently running</em> stream whose asset the caller's scope may not
     * reach (docs/plans/active/LIVE-SCOPE-PLAN.md §2, W2), is an error — the latter 404s exactly
     * like an unknown stream, so a caller polling a stream that just left their scope cannot tell
     * the two cases apart.
     *
     * <p>{@code lockedTrackId} is hoisted to the top level rather than living inside {@code stats}
     * (§4.E): it is the confirmed-from-the-wire held target, and the chip that reads it must stay
     * honest even when the window has no statistics to show.
     *
     * <p>{@code stats} is omitted whenever the window has not yet recorded a single detector pass —
     * a stream that has just started, or one with tracking off. That is exactly the "nothing to
     * report yet" case the flow strip hides itself for, and it keeps {@link
     * TrackStatsResponse#lastDetectorReason()} a real value in every response that carries the
     * object at all, rather than a half-populated strip of zeros.
     *
     * @param streamId the stream to inspect, as a canonical UUID string
     * @return the stream's tracks, its held target, the window's counters when there are any, and
     *         which detection gate currently explains its boxes-or-no-boxes state (docs/plans/active/CV-DEMAND-PLAN.md
     *         &sect;3.6)
     * @throws java.util.NoSuchElementException if {@code streamId} is currently running on a device
     *                                            whose asset the caller's scope may not reach
     */
    @GetMapping("/api/streams/{streamId}/tracks")
    public StreamTracksResponse tracks(@PathVariable String streamId) {
        StreamId id = StreamId.of(streamId);
        streamAccess.requireVisible(id);
        List<TrackResponse> tracks = streamService.tracks(id).stream()
                .filter(tracked -> tracked.detection().track() != null)
                .map(TrackResponse::from)
                .toList();
        TrackingStats stats = streamService.trackingStats(id).orElse(null);
        TrackStatsResponse statsResponse =
                stats == null || stats.lastDetectorReason() == null ? null : TrackStatsResponse.from(stats);
        // Gated on having sampled anything at all, NOT on `stats`: a stream with tracking off
        // reports latency and no stats, which is the combination this endpoint most needs to serve.
        PipelineLatencyResponse latencyResponse = streamService.pipelineLatency(id)
                .filter(latency -> latency.samples() > 0L)
                .map(PipelineLatencyResponse::from)
                .orElse(null);
        // Gated on a served deadline rather than on a completed one, so a stream whose samples are
        // ALL being dropped -- the case this object exists to diagnose -- still reports why.
        DetectionRateResponse rateResponse = streamService.detectionRate(id)
                .filter(rate -> rate.due() > 0L)
                .map(DetectionRateResponse::from)
                .orElse(null);
        DetectionState detectionState = streamService.detectionState(id).orElse(null);
        return new StreamTracksResponse(id.value().toString(), stats == null ? 0L : stats.lockedTrackId(), tracks,
                statsResponse, latencyResponse, rateResponse, detectionState);
    }

    /**
     * Lists a stream's most recent completed detection results, newest first (docs/plans/done/MVP1-PLAN.md
     * §C8 bullet 3) — for the Live page's detections strip.
     *
     * <p>An unknown stream id behaves exactly as {@link DetectionRepositoryPort#query} does (an
     * empty list, per its driven-port contract), not a 404 — mirroring {@link
     * AssetController#telemetry}'s precedent for the same reason: this endpoint has no service
     * method of its own to layer "unknown stream" validation onto. Results are sorted here rather
     * than relied upon to already be in order, since {@link DetectionRepositoryPort#query}'s
     * contract does not guarantee one.
     *
     * @param streamId the stream to inspect, as a canonical UUID string
     * @param limit    maximum number of results to return; must be positive (400 otherwise, via
     *                 {@link DetectionQuery}'s own validation); defaults to {@value
     *                 #DEFAULT_DETECTIONS_LIMIT}
     * @return the stream's recent detection results, newest first
     * @throws java.util.NoSuchElementException if {@code streamId} is currently running on a device
     *                                            whose asset the caller's scope may not reach
     *                                            (docs/plans/active/LIVE-SCOPE-PLAN.md §2, W2) — an
     *                                            unknown/stopped stream keeps its pre-existing empty
     *                                            200
     */
    @GetMapping("/api/streams/{streamId}/detections")
    public List<DetectionResultResponse> detections(
            @PathVariable String streamId,
            @RequestParam(defaultValue = "" + DEFAULT_DETECTIONS_LIMIT) int limit) {
        StreamId id = StreamId.of(streamId);
        // limit validation (a non-positive value is a 400) runs before the scope guard's 404, same
        // ordering as #start/#updateConfig.
        DetectionQuery query = new DetectionQuery(id, null, null, null, limit);
        streamAccess.requireVisible(id);
        // This read is itself detection demand (docs/plans/active/CV-DEMAND-PLAN.md §3.5, the
        // poll half): a Wall/Live page polling this endpoint keeps the stream detecting, exactly
        // like an open SSE `detections:<assetId>` subscription does. Touched only once the caller is
        // known to be allowed to read this stream at all.
        streamDetectionSupport.touched(id);
        return detectionRepositoryPort.query(query).stream()
                .sorted(Comparator.comparing(DetectionResult::capturedAt).reversed())
                .map(DetectionResultResponse::from)
                .toList();
    }

    /**
     * A JPEG snapshot of the latest published frame on a running stream (docs/plans/done/MVP3-PLAN.md C-a) —
     * always the clean, undecorated frame (docs/plans/active/CV-CLEAN-FEED-PLAN.md D-1: server-side overlay
     * burn-in no longer exists), since {@link StreamService#latestFrame} returns exactly
     * the instance the pipeline last handed to {@link StreamPublisherPort#publish}. Downscaled to
     * at most {@value SnapshotJpegEncoder#MAX_SNAPSHOT_WIDTH}px wide (aspect-preserving, see {@link
     * SnapshotJpegEncoder}) so a manager dashboard polling many thumbnails at once (docs/plans/done/MVP3-PLAN.md
     * Command, ~1/5s per visible tile) stays cheap.
     *
     * <p>Never cached ({@code Cache-Control: no-store}) — every poll wants the actual latest frame,
     * not a browser- or intermediary-cached one.
     *
     * @param streamId the stream to snapshot, as a canonical UUID string
     * @return the JPEG bytes
     * @throws NoSuchElementException if the stream is unknown, is running but hasn't published a
     *                                 frame yet, or is running but the caller's scope may not reach
     *                                 its asset (docs/plans/active/LIVE-SCOPE-PLAN.md §2, W2) — all
     *                                 three → 404, same mapping as every other unknown-id case in
     *                                 this codebase
     */
    @GetMapping(value = "/api/streams/{streamId}/snapshot", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> snapshot(@PathVariable String streamId) {
        StreamId id = StreamId.of(streamId);
        streamAccess.requireVisible(id);
        VideoFrame frame = streamService.latestFrame(id)
                .orElseThrow(() -> new NoSuchElementException("No frame available for stream: " + streamId));
        byte[] jpeg = snapshotJpegEncoder.encode(frame);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noStore())
                .body(jpeg);
    }

    private String viewUrl(StreamId streamId) {
        return streamPublisherPort.viewUrl(streamId).map(URI::toString).orElse(null);
    }

    private String whepUrl(StreamId streamId) {
        return streamPublisherPort.whepUrl(streamId).map(URI::toString).orElse(null);
    }
}

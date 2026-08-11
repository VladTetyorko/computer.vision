package com.drones.vision.api.controller;

import com.drones.vision.api.dto.AssetDeletionResponse;
import com.drones.vision.api.dto.AssetDetailsResponse;
import com.drones.vision.api.dto.AssetSummaryResponse;
import com.drones.vision.api.dto.AssignDeviceRequest;
import com.drones.vision.api.dto.CreateAssetRequest;
import com.drones.vision.api.dto.SetLifecycleStateRequest;
import com.drones.vision.api.dto.StartAssetStreamRequest;
import com.drones.vision.api.dto.StartStreamResponse;
import com.drones.vision.api.dto.TelemetrySampleResponse;
import com.drones.vision.api.dto.UpdateAssetRequest;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.perception.application.stream.TrackingConfigPatch;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.warehouse.domain.port.AssetImageRepositoryPort;
import com.drones.vision.perception.domain.port.StreamPublisherPort;
import com.drones.vision.flight.domain.port.TelemetryRepositoryPort;
import org.springframework.http.HttpStatus;
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
import java.util.List;
import java.util.Objects;
import com.drones.vision.api.security.CurrentUser;

/**
 * Driving REST adapter for the asset-first control-plane flow: create an
 * asset (with its device(s)) in one call, list/inspect assets as the read
 * models the UI leads with, and start/stop streaming at the asset level.
 * Also exposes a usage's raw telemetry trail (unwindowed, un-downsampled — see
 * {@link #telemetry}). For a windowed, downsampled, 404-on-unknown-usage replay view (telemetry
 * plus, in future, detections), see {@link UsageTimelineController} instead (docs/plans/done/MVP2-PLAN.md
 * §R, R-a) — the two endpoints live on separate controllers, see that class's javadoc for why.
 *
 * <p>Constructor-injected with {@link AssetService}, {@link CurrentUser}, and three driven ports
 * used read-only: {@link StreamPublisherPort} (resolving {@code viewUrl}/{@code whepUrl}, exactly
 * like {@link StreamController}), {@link TelemetryRepositoryPort} (serving the telemetry endpoint — there
 * is no service method for "read a usage's telemetry trail" yet, so this controller reads the
 * driven port directly, the same precedent {@link StreamController} already sets for {@code
 * viewUrl}), and {@link AssetImageRepositoryPort} (populating {@code hasImage} on every summary/
 * detail response — docs/plans/done/UX-REWORK-PLAN.md §U-d item 3, CONTRACT 2 — via its cheap {@code
 * existsByAssetId} check; the image bytes themselves are served by {@link AssetImageController}).
 * Per the hexagonal dependency rule (ARCHITECTURE.md §2, enforced by ArchUnit), this
 * module depends only on {@code vision-domain} and {@code vision-application} — never on an
 * adapter.
 *
 * <h2>Who the change is attributed to</h2>
 * The acting user comes from {@link CurrentUser} and is passed to every mutating call, so the
 * audit trail records a principal without any service knowing how it was authenticated.
 *
 * <h2>Visibility scoping (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2, feature 1)</h2>
 * Every read is scoped to {@link CurrentUser#scope()}: {@link #list} filters to the assets the
 * caller may see, and {@link #details} (and every post-mutation detail render) 404s an asset
 * outside the caller's scope exactly as it 404s an unknown id — existence is never revealed. Each
 * mutation ({@link #update}/{@link #setState}/{@link #delete}/{@link #assignDevice}/{@link
 * #unassignDevice}/{@link #startStream}) first re-reads through the scope, so an out-of-scope asset
 * 404s before the mutation runs; wave 1 scoped only asset reads + command + assign, so this
 * cheap "read-scope guards the write" is the deliberate write-path posture until the asset services
 * take a scope on writes directly. With auth off the scope is unbounded, so all of this is a no-op
 * and behavior is identical to before scoping.
 *
 * <h2>Status codes</h2>
 * An unknown asset id surfaces as {@link java.util.NoSuchElementException} from {@link
 * AssetService} and maps to 404 through {@link ApiExceptionHandler}; a malformed UUID fails
 * earlier in {@code AssetId.of}/{@code DeviceId.of} and maps to 400, as do genuine validation
 * failures such as an ambiguous device or a device that does not belong to the asset.
 */
@RestController
public class AssetController {

    private final AssetService assetService;
    private final CurrentUser currentUser;
    private final StreamPublisherPort streamPublisherPort;
    private final TelemetryRepositoryPort telemetryRepositoryPort;
    private final AssetImageRepositoryPort assetImageRepositoryPort;

    public AssetController(AssetService assetService, CurrentUser currentUser,
                            StreamPublisherPort streamPublisherPort,
                            TelemetryRepositoryPort telemetryRepositoryPort,
                            AssetImageRepositoryPort assetImageRepositoryPort) {
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
        this.streamPublisherPort =
                Objects.requireNonNull(streamPublisherPort, "streamPublisherPort must not be null");
        this.telemetryRepositoryPort =
                Objects.requireNonNull(telemetryRepositoryPort, "telemetryRepositoryPort must not be null");
        this.assetImageRepositoryPort =
                Objects.requireNonNull(assetImageRepositoryPort, "assetImageRepositoryPort must not be null");
    }

    /**
     * Creates a new asset together with its device(s) in one call — the
     * asset-first registration path the UI leads with.
     *
     * @param request the asset to create
     * @return the full detail view of the newly created asset
     */
    @PostMapping("/api/assets")
    @ResponseStatus(HttpStatus.CREATED)
    public AssetDetailsResponse create(@RequestBody CreateAssetRequest request) {
        Asset created = assetService.create(request.toSpec(), currentUser.ownership(), currentUser.userId());
        return detailsResponse(created.id());
    }

    /**
     * Applies a partial edit to an asset.
     *
     * <p>{@code PATCH} rather than {@code PUT} because the body is a set of changes, not a
     * replacement: omitted fields keep their current values.
     *
     * @param id      the asset to edit
     * @param request the fields to change; an absent body changes nothing
     * @return the full detail view of the edited asset
     */
    @PatchMapping("/api/assets/{id}")
    public AssetDetailsResponse update(@PathVariable String id,
                                        @RequestBody(required = false) UpdateAssetRequest request) {
        AssetId assetId = AssetId.of(id);
        // Parse/validate the body (a malformed edit is a 400) before the scope guard's 404, so a
        // bad request never depends on the caller's scope.
        var edit = (request == null ? UpdateAssetRequest.EMPTY : request).toEdit();
        requireInScope(assetId);
        assetService.update(assetId, edit, currentUser.userId());
        return detailsResponse(assetId);
    }

    /**
     * Moves an asset between {@code ACTIVE} and {@code DEACTIVATED} (docs/main/CYCLES-PLAN.md §8's
     * pinned contract).
     *
     * <p>Idempotent. {@code DEACTIVATED} on an already-{@code DELETED} asset restores it —
     * recovering something that was deleted should not put it back on the air in the same
     * action, so activating afterward is a second, deliberate step. Requesting {@code ACTIVE} on
     * a deleted asset is refused (409): restore first.
     *
     * @param id      the asset to move
     * @param request the state to move it to; {@code ACTIVE} or {@code DEACTIVATED}
     * @return the full detail view of the asset in its new state
     */
    @PostMapping("/api/assets/{id}/state")
    public AssetDetailsResponse setState(@PathVariable String id, @RequestBody SetLifecycleStateRequest request) {
        AssetId assetId = AssetId.of(id);
        var state = request.toLifecycleState(); // an unrecognized state is a 400, before the scope 404
        requireInScope(assetId);
        assetService.setState(assetId, state, currentUser.userId());
        return detailsResponse(assetId);
    }

    /**
     * Removes an asset and its sources from service and from view — a soft delete.
     *
     * <p>Streams stop, the asset and its devices are hidden, but nothing is destroyed: usages and
     * telemetry are kept and the removal can be undone by {@link #setState}'s restore semantics.
     * Returns 200 with a summary of what was affected and what was preserved, rather than an
     * empty 204.
     *
     * @param id the asset to delete
     * @return what the deletion affected, and what it kept
     */
    @DeleteMapping("/api/assets/{id}")
    public AssetDeletionResponse delete(@PathVariable String id) {
        AssetId assetId = AssetId.of(id);
        requireInScope(assetId);
        return AssetDeletionResponse.from(assetService.delete(assetId, currentUser.userId()));
    }

    /**
     * Lists assets as user-facing summaries.
     *
     * @param includeDeleted whether to include soft-deleted assets; excluded by default, so
     *                       "deleted" behaves as deleted unless a view explicitly asks otherwise
     * @return the current asset summaries
     */
    @GetMapping("/api/assets")
    public List<AssetSummaryResponse> list(@RequestParam(defaultValue = "false") boolean includeDeleted) {
        return assetService.assets(currentUser.scope(), includeDeleted).stream()
                .map(summary -> AssetSummaryResponse.from(summary,
                        assetImageRepositoryPort.existsByAssetId(summary.asset().id())))
                .toList();
    }

    /**
     * Fetches the full detail view for one asset.
     *
     * @param id the asset id, as a canonical UUID string
     * @return the asset's detail view
     */
    @GetMapping("/api/assets/{id}")
    public AssetDetailsResponse details(@PathVariable String id) {
        return detailsResponse(AssetId.of(id));
    }

    /**
     * Starts a stream for one of the asset's devices. The optional request
     * body's fields, if present, override the corresponding defaults (see
     * {@link StartAssetStreamRequest}); {@code deviceId} absent means "the
     * asset's single video-capable device".
     *
     * <p>What the body says about {@code tracking} travels as its own patch, folded onto the
     * deployment's tracking seed inside the application layer — the same path {@code
     * StreamController#start} and the simulation service take, so the deployment default never
     * depends on which button the operator pressed (docs/extracts/TRACKING-ORCHESTRATION.md §4.1).
     *
     * @param id      the asset to stream from, as a canonical UUID string
     * @param request optional overrides; {@code null}/absent means use every default
     * @return the started stream's id and (if available) its viewer URLs
     */
    @PostMapping("/api/assets/{id}/stream")
    @ResponseStatus(HttpStatus.CREATED)
    public StartStreamResponse startStream(@PathVariable String id,
                                            @RequestBody(required = false) StartAssetStreamRequest request) {
        AssetId assetId = AssetId.of(id);
        StartAssetStreamRequest effective = request == null ? StartAssetStreamRequest.EMPTY : request;
        DeviceId device = effective.deviceIdOrNull(); // malformed device id / config is a 400, before the scope 404
        PipelineConfig config = effective.mergeOntoDefaults();
        TrackingConfigPatch tracking = effective.trackingPatch();
        requireInScope(assetId);

        StreamId streamId = assetService.startStream(assetId, device, config, tracking);
        return new StartStreamResponse(streamId.value().toString(), viewUrl(streamId), whepUrl(streamId));
    }

    /**
     * Stops the asset's active stream(s), if any. Idempotent — an asset with
     * no active stream (or an unknown asset id) is a no-op, mirroring {@link
     * AssetService#stopStream(AssetId)}'s contract, so there is no 404 case
     * here.
     *
     * @param id the asset to stop streaming, as a canonical UUID string
     */
    @DeleteMapping("/api/assets/{id}/stream")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stopStream(@PathVariable String id) {
        assetService.stopStream(AssetId.of(id));
    }

    /**
     * Assigns an existing, unowned device to this asset (docs/main/CYCLES-PLAN.md §8's pinned
     * contract).
     *
     * @param id      the asset to assign the device to, as a canonical UUID string
     * @param request the device to assign
     * @return the full detail view of the asset with the device now attached
     */
    @PostMapping("/api/assets/{id}/devices")
    public AssetDetailsResponse assignDevice(@PathVariable String id, @RequestBody AssignDeviceRequest request) {
        AssetId assetId = AssetId.of(id);
        var deviceId = request.toDeviceId(); // a blank device id is a 400, before the scope 404
        requireInScope(assetId);
        assetService.assignDevice(assetId, deviceId, currentUser.userId());
        return detailsResponse(assetId);
    }

    /**
     * Removes one of this asset's devices, leaving the device itself untouched (docs/main/CYCLES-PLAN.md
     * §8's pinned contract).
     *
     * @param id       the asset to unassign the device from, as a canonical UUID string
     * @param deviceId the device to unassign, as a canonical UUID string
     * @return the full detail view of the asset without the device
     */
    @DeleteMapping("/api/assets/{id}/devices/{deviceId}")
    public AssetDetailsResponse unassignDevice(@PathVariable String id, @PathVariable String deviceId) {
        AssetId assetId = AssetId.of(id);
        DeviceId device = DeviceId.of(deviceId); // a malformed device UUID is a 400, before the scope 404
        requireInScope(assetId);
        assetService.unassignDevice(assetId, device, currentUser.userId());
        return detailsResponse(assetId);
    }

    /**
     * Lists telemetry samples recorded for a usage — for a future map/trail
     * view; plain JSON for now. An unknown usage id behaves exactly as
     * {@link TelemetryRepositoryPort#findByUsage} does (an empty list, per
     * its driven-port contract), not a 404 — this endpoint has no service
     * method of its own to layer "unknown usage" validation onto.
     *
     * <p>Unwindowed and undownsampled — every sample up to {@code limit}, earliest first (see
     * {@link TelemetryRepositoryPort#findByUsage}'s gotcha). {@link UsageTimelineController}'s
     * {@code GET /api/usages/{usageId}/timeline} is the endpoint actually meant for replaying a
     * long flight.
     *
     * @param usageId the usage id, as a canonical UUID string
     * @param limit   maximum number of samples to return; defaults to 100
     * @return the usage's telemetry samples
     */
    @GetMapping("/api/usages/{usageId}/telemetry")
    public List<TelemetrySampleResponse> telemetry(@PathVariable String usageId,
                                                     @RequestParam(defaultValue = "100") int limit) {
        return telemetryRepositoryPort.findByUsage(UsageId.of(usageId), limit).stream()
                .map(TelemetrySampleResponse::from)
                .toList();
    }

    /**
     * Fetches {@code id}'s detail view plus its {@code hasImage} flag in one call, scoped to the
     * caller — an asset outside {@link CurrentUser#scope()} 404s exactly as an unknown id does.
     */
    private AssetDetailsResponse detailsResponse(AssetId id) {
        return AssetDetailsResponse.from(assetService.details(currentUser.scope(), id),
                assetImageRepositoryPort.existsByAssetId(id));
    }

    /**
     * Guards a mutation: re-reads {@code id} through the caller's scope so an out-of-scope (or
     * unknown) asset 404s ({@link java.util.NoSuchElementException}) before the mutation runs. Cheap
     * — the same scoped read the detail endpoint does — and the deliberate write-path posture until
     * the asset services take a {@code VisibilityScope} on writes directly (see the class javadoc).
     */
    private void requireInScope(AssetId id) {
        assetService.details(currentUser.scope(), id);
    }

    private String viewUrl(StreamId streamId) {
        return streamPublisherPort.viewUrl(streamId).map(URI::toString).orElse(null);
    }

    private String whepUrl(StreamId streamId) {
        return streamPublisherPort.whepUrl(streamId).map(URI::toString).orElse(null);
    }
}

package com.drones.vision.api;

import com.drones.vision.api.dto.AssetDetailsResponse;
import com.drones.vision.api.dto.AssetSummaryResponse;
import com.drones.vision.api.dto.CreateAssetRequest;
import com.drones.vision.api.dto.DeletionSummaryResponse;
import com.drones.vision.api.dto.StartAssetStreamRequest;
import com.drones.vision.api.dto.StartStreamResponse;
import com.drones.vision.api.dto.TelemetrySampleResponse;
import com.drones.vision.api.dto.UpdateAssetRequest;
import com.drones.vision.application.AssetService;
import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.LifecycleState;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.UsageId;
import com.drones.vision.domain.port.out.StreamPublisherPort;
import com.drones.vision.domain.port.out.TelemetryRepositoryPort;
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

/**
 * Driving REST adapter for the asset-first control-plane flow: create an
 * asset (with its device(s)) in one call, list/inspect assets as the read
 * models the UI leads with, and start/stop streaming at the asset level.
 * Also exposes a usage's telemetry trail (for a future map/trail view).
 *
 * <p>Constructor-injected with {@link AssetService}, {@link CurrentUser}, and two driven ports
 * used read-only: {@link StreamPublisherPort} (resolving {@code viewUrl}, exactly like {@link
 * StreamController}) and {@link TelemetryRepositoryPort} (serving the telemetry endpoint — there
 * is no service method for "read a usage's telemetry trail" yet, so this controller reads the
 * driven port directly, the same precedent {@link StreamController} already sets for {@code
 * viewUrl}). Per the hexagonal dependency rule (ARCHITECTURE.md §2, enforced by ArchUnit), this
 * module depends only on {@code vision-domain} and {@code vision-application} — never on an
 * adapter.
 *
 * <h2>Who the change is attributed to</h2>
 * The acting user comes from {@link CurrentUser} and is passed to every mutating call, so the
 * audit trail records a principal without any service knowing how it was authenticated.
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

    public AssetController(AssetService assetService, CurrentUser currentUser,
                            StreamPublisherPort streamPublisherPort,
                            TelemetryRepositoryPort telemetryRepositoryPort) {
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
        this.streamPublisherPort =
                Objects.requireNonNull(streamPublisherPort, "streamPublisherPort must not be null");
        this.telemetryRepositoryPort =
                Objects.requireNonNull(telemetryRepositoryPort, "telemetryRepositoryPort must not be null");
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
        return AssetDetailsResponse.from(assetService.details(created.id()));
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
        assetService.update(assetId, (request == null ? UpdateAssetRequest.EMPTY : request).toEdit(),
                currentUser.userId());
        return AssetDetailsResponse.from(assetService.details(assetId));
    }

    /**
     * Takes an asset out of service, stopping whatever it is streaming.
     *
     * <p>The reversible alternative to {@link #delete(String)}: devices, usage history and
     * telemetry all survive, but the asset refuses to stream until reactivated.
     *
     * @param id the asset to deactivate
     * @return the full detail view of the deactivated asset
     */
    @PostMapping("/api/assets/{id}/deactivate")
    public AssetDetailsResponse deactivate(@PathVariable String id) {
        return setState(id, LifecycleState.DEACTIVATED);
    }

    /**
     * Puts a deactivated asset back into service.
     *
     * @param id the asset to reactivate
     * @return the full detail view of the reactivated asset
     */
    @PostMapping("/api/assets/{id}/activate")
    public AssetDetailsResponse activate(@PathVariable String id) {
        return setState(id, LifecycleState.ACTIVE);
    }

    /**
     * Removes an asset and its sources from service and from view — a soft delete.
     *
     * <p>Streams stop, the asset and its devices are hidden, but nothing is destroyed: usages and
     * telemetry are kept and the removal can be undone with {@link #restore(String)}. Returns 200
     * with a summary of what was affected and what was preserved, rather than an empty 204.
     *
     * @param id the asset to delete
     * @return what the deletion affected, and what it kept
     */
    @DeleteMapping("/api/assets/{id}")
    public DeletionSummaryResponse delete(@PathVariable String id) {
        return DeletionSummaryResponse.from(assetService.delete(AssetId.of(id), currentUser.userId()));
    }

    /**
     * Brings a soft-deleted asset back, out of service.
     *
     * <p>Restores to {@code DEACTIVATED}, never straight to {@code ACTIVE}: recovering something
     * that was deleted should not put it back on the air in the same action. Activating is a
     * second, deliberate step — and both are audited.
     *
     * @param id the asset to restore
     * @return the full detail view of the restored asset
     */
    @PostMapping("/api/assets/{id}/restore")
    public AssetDetailsResponse restore(@PathVariable String id) {
        return setState(id, LifecycleState.DEACTIVATED);
    }

    private AssetDetailsResponse setState(String id, LifecycleState state) {
        AssetId assetId = AssetId.of(id);
        assetService.setState(assetId, state, currentUser.userId());
        return AssetDetailsResponse.from(assetService.details(assetId));
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
        return assetService.assets(includeDeleted).stream().map(AssetSummaryResponse::from).toList();
    }

    /**
     * Fetches the full detail view for one asset.
     *
     * @param id the asset id, as a canonical UUID string
     * @return the asset's detail view
     */
    @GetMapping("/api/assets/{id}")
    public AssetDetailsResponse details(@PathVariable String id) {
        return AssetDetailsResponse.from(assetService.details(AssetId.of(id)));
    }

    /**
     * Starts a stream for one of the asset's devices. The optional request
     * body's fields, if present, override the corresponding defaults (see
     * {@link StartAssetStreamRequest}); {@code deviceId} absent means "the
     * asset's single video-capable device".
     *
     * @param id      the asset to stream from, as a canonical UUID string
     * @param request optional overrides; {@code null}/absent means use every default
     * @return the started stream's id and (if available) its viewer URL
     */
    @PostMapping("/api/assets/{id}/stream")
    @ResponseStatus(HttpStatus.CREATED)
    public StartStreamResponse startStream(@PathVariable String id,
                                            @RequestBody(required = false) StartAssetStreamRequest request) {
        AssetId assetId = AssetId.of(id);
        StartAssetStreamRequest effective = request == null ? StartAssetStreamRequest.EMPTY : request;
        DeviceId device = effective.deviceIdOrNull();
        PipelineConfig config = effective.mergeOntoDefaults();

        StreamId streamId = assetService.startStream(assetId, device, config);
        return new StartStreamResponse(streamId.value().toString(), viewUrl(streamId));
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
     * Lists telemetry samples recorded for a usage — for a future map/trail
     * view; plain JSON for now. An unknown usage id behaves exactly as
     * {@link TelemetryRepositoryPort#findByUsage} does (an empty list, per
     * its driven-port contract), not a 404 — this endpoint has no service
     * method of its own to layer "unknown usage" validation onto.
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

    private String viewUrl(StreamId streamId) {
        return streamPublisherPort.viewUrl(streamId).map(URI::toString).orElse(null);
    }
}

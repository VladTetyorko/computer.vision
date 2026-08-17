package com.drones.vision.api.controller;

import com.drones.vision.api.dto.AssetDeletionResponse;
import com.drones.vision.api.dto.AssetDetailsResponse;
import com.drones.vision.api.dto.AssetSummaryResponse;
import com.drones.vision.api.dto.AssignDeviceRequest;
import com.drones.vision.api.dto.CreateAssetRequest;
import com.drones.vision.api.dto.SetLifecycleStateRequest;
import com.drones.vision.api.dto.TelemetrySampleResponse;
import com.drones.vision.api.dto.UpdateAssetRequest;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.warehouse.application.asset.AssetDetails;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.warehouse.domain.port.AssetImageRepositoryPort;
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

import java.util.List;
import java.util.Objects;
import com.drones.vision.api.security.CurrentUser;

/**
 * Driving REST adapter for the asset-first control-plane flow: create an
 * asset (with its device(s)) in one call, and list/inspect assets as the read
 * models the UI leads with. Also exposes a usage's raw telemetry trail (unwindowed,
 * un-downsampled — see {@link #telemetry}). For a windowed, downsampled, 404-on-unknown-usage
 * replay view (telemetry plus, in future, detections), see {@link UsageTimelineController} instead
 * (docs/plans/done/MVP2-PLAN.md §R, R-a) — the two endpoints live on separate controllers, see that
 * class's javadoc for why. **Starting/stopping a stream lives on {@link AssetStreamController}**
 * (split off in docs/plans/active/DOMAIN-SEPARATION-W1.md §15, W1.6e — adding {@code
 * AssetStreamService} here would have pushed this constructor to six parameters, one past
 * .claude/skills/java-clean-code/SKILL.md §3's five-parameter ceiling; see that class's own
 * javadoc), not here.
 *
 * <p>Constructor-injected with {@link AssetService}, {@link CurrentUser}, and two driven ports
 * used read-only: {@link TelemetryRepositoryPort} (serving the telemetry endpoint — there is no
 * service method for "read a usage's telemetry trail" yet, so this controller reads the driven
 * port directly, the same precedent {@link StreamController} sets for {@code viewUrl}), and {@link
 * AssetImageRepositoryPort} (populating {@code hasImage} on every summary/detail response —
 * docs/plans/done/UX-REWORK-PLAN.md §U-d item 3, CONTRACT 2 — via its cheap {@code
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
 * outside the caller's scope exactly as it 404s an unknown id — existence is never revealed.
 *
 * <h2>Authority, not visibility, guards the writes (docs/plans/active/OPS-UX-PLAN.md §1)</h2>
 * Seeing an asset and administering it are different questions — a PILOT's scope is built to let
 * them see (and fly) exactly the aircraft assigned to them, which is not authority to rename,
 * deactivate, delete, or reassign the devices of that same aircraft. Each mutation ({@link
 * #update}/{@link #setState}/{@link #delete}/{@link #assignDevice}/{@link #unassignDevice}) calls
 * {@link #requireManageable}, which first re-reads the asset through the scope exactly as before
 * (an unknown or out-of-scope asset still 404s, hiding existence — the caller cannot even ask about
 * something they cannot see), then additionally requires {@link
 * com.drones.vision.platform.VisibilityScope#canManage(com.drones.vision.kernel.Ownership)
 * scope().canManage(ownership)} — an asset the caller can see but does not administer now 403s,
 * an honest "you may not do this" rather than a hiding 404, matching every other command gate in
 * this codebase (see {@link ApiExceptionHandler}'s 403 mapping). {@link #create} gains its own
 * gate, {@link com.drones.vision.platform.VisibilityScope#canManageOrg() scope().canManageOrg()} —
 * registering a new asset is team-scoped management, the same gate {@code DatasetService#create}/
 * {@code UserService#create} already use, not the deployment-global {@code canAdminister()} the
 * write gate above deliberately avoids needing (a MANAGER may administer every asset in their own
 * subtree without being an ADMIN). With auth off the scope is unbounded, so every one of these
 * gates passes and behavior is identical to before this wave.
 *
 * <h2>Status codes</h2>
 * An unknown asset id surfaces as {@link java.util.NoSuchElementException} from {@link
 * AssetService} and maps to 404 through {@link ApiExceptionHandler}; a malformed UUID fails
 * earlier in {@code AssetId.of}/{@code DeviceId.of} and maps to 400, as do genuine validation
 * failures such as a device that does not belong to the asset.
 */
@RestController
public class AssetController {

    private final AssetService assetService;
    private final CurrentUser currentUser;
    private final TelemetryRepositoryPort telemetryRepositoryPort;
    private final AssetImageRepositoryPort assetImageRepositoryPort;

    public AssetController(AssetService assetService, CurrentUser currentUser,
                            TelemetryRepositoryPort telemetryRepositoryPort,
                            AssetImageRepositoryPort assetImageRepositoryPort) {
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
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
        if (!currentUser.scope().canManageOrg()) {
            throw new AccessDeniedException("Not permitted to register new assets");
        }
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
        // Renaming an assigned aircraft or editing its custom fields is an operator act, not a
        // management one — see AssetEdit#changesManagedFields for why the split is on the body
        // rather than on the endpoint.
        if (edit.changesManagedFields()) {
            requireManageable(assetId);
        } else {
            requireVisible(assetId);
        }
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
        requireManageable(assetId);
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
        requireManageable(assetId);
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
        requireManageable(assetId);
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
        requireManageable(assetId);
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
     * Guards a mutation: re-reads {@code id} through the caller's scope, so an out-of-scope or
     * unknown asset 404s ({@link java.util.NoSuchElementException}, hiding existence, unchanged
     * from before this wave) before the mutation runs — then, for an asset the caller can see,
     * additionally requires {@link com.drones.vision.platform.VisibilityScope#canManage
     * scope().canManage(ownership)}, an honest 403 rather than a hiding 404 (see the class
     * javadoc's "Authority, not visibility" section for why the second check exists).
     *
     * <p>{@link #requireVisible} is the first half alone — the 404 without the 403 — used by {@link
     * #update} for an edit that only touches operator-editable fields ({@link
     * com.drones.vision.warehouse.application.asset.AssetEdit#changesManagedFields}).
     */
    private void requireVisible(AssetId id) {
        assetService.details(currentUser.scope(), id);
    }

    private void requireManageable(AssetId id) {
        AssetDetails details = assetService.details(currentUser.scope(), id);
        if (!currentUser.scope().canManage(details.summary().asset().ownership())) {
            throw new AccessDeniedException("Asset " + id.value() + " is outside your management authority");
        }
    }
}

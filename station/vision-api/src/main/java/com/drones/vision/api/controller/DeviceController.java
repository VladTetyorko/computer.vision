package com.drones.vision.api.controller;

import com.drones.vision.api.dto.DeviceResponse;
import com.drones.vision.api.dto.RegisterDeviceRequest;
import com.drones.vision.api.dto.SetLifecycleStateRequest;
import com.drones.vision.api.dto.UpdateDeviceRequest;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.StreamAccess;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.warehouse.application.device.DeviceService;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import com.drones.vision.api.security.CurrentUser;

/**
 * Driving REST adapter for the life of a device: registering, listing,
 * editing, taking out of service, and deleting.
 *
 * <p>Constructor-injected with {@link DeviceService}, {@link CurrentUser} and {@link StreamAccess}.
 * Per the hexagonal dependency rule (ARCHITECTURE.md §2, enforced by ArchUnit), this module depends
 * only on {@code vision-domain} and {@code vision-application} — never on an adapter.
 *
 * <h2>Who the change is attributed to</h2>
 * The acting user comes from {@link CurrentUser} and is passed to every mutating call, so the
 * audit trail records a principal without {@link DeviceService} knowing how it was authenticated.
 * That attribution is not authorization — the platform audit behind
 * docs/plans/done/LIVE-SCOPE-PLAN.md §2 (W1) found every handler here passing {@code userId()}
 * and concluding, wrongly, that this controller was already guarded. It was not: see below.
 *
 * <h2>Authority (docs/plans/done/LIVE-SCOPE-PLAN.md §2.2, W5)</h2>
 * {@link #register}/{@link #delete} require {@link com.drones.vision.platform.VisibilityScope#canManageOrg()
 * scope().canManageOrg()} — the same org-level gate {@code AssetController#create} uses, not the
 * deployment-global {@code canAdminister()}. Both operations act on the device row's existence, not
 * on any specific asset's group: {@link Device} carries no {@code Ownership} field of its own (only
 * an {@code Asset} does, once a device is assigned to one), so there is no per-group boundary to
 * check against a device that may not even be assigned yet.
 *
 * <p>{@link #update}/{@link #setState} instead require the caller be able to <em>reach</em> the
 * device through {@link StreamAccess#requireVisible(DeviceId)} — the device→asset→owner resolution
 * {@link StreamAccess} already performs for the live-operations surface (LIVE-SCOPE W2), reused
 * here rather than a second lookup. Deliberately not {@code canManage(ownership)}: that predicate is
 * hardcoded {@code false} for every {@code ASSIGNED_ASSETS} (PILOT) scope regardless of the asset,
 * which would 403 a PILOT editing or retiring their own assigned camera — exactly the case this wave
 * must keep working (mirrors the identical W2 correction {@link StreamAccess}'s own javadoc
 * documents for stream writes).
 *
 * <p>{@link #list} is filtered via {@link StreamAccess#filterVisibleDevices(List)} rather than
 * all-or-nothing 403'd, matching {@code StreamController#list}'s own precedent. A device that
 * belongs to no asset at all is reachable only by a caller whose scope {@code canAdminister()} —
 * {@link StreamAccess}'s own pre-existing ruling for an unowned device (LIVE-SCOPE W2), reused
 * rather than re-decided here.
 *
 * <p>No {@code VisibilityScope} reaches {@link DeviceService} itself — every check above runs in
 * this controller, before the service is ever called, the same layering {@code AssetController}
 * already uses (its mutate methods take no scope either; only its scoped <em>reads</em> do).
 *
 * <h2>Status codes for lifecycle operations</h2>
 * Unknown ids surface as {@link java.util.NoSuchElementException} from {@link DeviceService} and
 * map to 404 through {@link ApiExceptionHandler}; a malformed UUID fails earlier in {@code
 * DeviceId.of} and maps to 400. Deleting an asset's last remaining device is refused with {@link
 * IllegalStateException} → 409, since an asset must always hold at least one device.
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;
    private final CurrentUser currentUser;
    private final StreamAccess streamAccess;

    public DeviceController(DeviceService deviceService, CurrentUser currentUser, StreamAccess streamAccess) {
        this.deviceService = Objects.requireNonNull(deviceService, "deviceService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
        this.streamAccess = Objects.requireNonNull(streamAccess, "streamAccess must not be null");
    }

    /**
     * Registers a new device. {@code capabilities} in the request is optional
     * and defaults to {@code VIDEO} only when absent/empty (see
     * {@link RegisterDeviceRequest}).
     *
     * @param request the device to register
     * @return the registered device
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceResponse register(@RequestBody RegisterDeviceRequest request) {
        requireManageOrg();
        Device device = deviceService.register(request.toRegistration(), currentUser.userId());
        return DeviceResponse.from(device);
    }

    /**
     * Lists registered devices, filtered to the ones {@link CurrentUser#scope()} may reach
     * (docs/plans/done/LIVE-SCOPE-PLAN.md §2.2, W5) — a PILOT sees their own assigned assets'
     * devices, never the whole fleet.
     *
     * @param includeDeleted whether to include soft-deleted devices; excluded by default, so
     *                       "deleted" behaves as deleted unless a view explicitly asks otherwise
     * @return the currently registered devices visible to the caller
     */
    @GetMapping
    public List<DeviceResponse> list(@RequestParam(defaultValue = "false") boolean includeDeleted) {
        List<Device> devices = deviceService.devices(includeDeleted);
        return streamAccess.filterVisibleDevices(devices).stream().map(DeviceResponse::from).toList();
    }

    /**
     * Applies a partial edit to a device — rename, re-point at a new URI, retune options.
     *
     * <p>A running stream keeps its current settings; the edit takes effect on the next start,
     * because reconnecting a live stream underneath a viewer without being asked is worse than
     * making the restart explicit.
     *
     * @param id      the device to edit
     * @param request the fields to change; an absent body changes nothing
     * @return the edited device
     */
    @PatchMapping("/{id}")
    public DeviceResponse update(@PathVariable String id,
                                  @RequestBody(required = false) UpdateDeviceRequest request) {
        DeviceId deviceId = DeviceId.of(id);
        // Parse/validate the body (a malformed edit is a 400) before the scope guard's 404, so a
        // bad request never depends on the caller's scope.
        var edit = (request == null ? UpdateDeviceRequest.EMPTY : request).toEdit();
        streamAccess.requireVisible(deviceId);
        Device updated = deviceService.update(deviceId, edit, currentUser.userId());
        return DeviceResponse.from(updated);
    }

    /**
     * Moves a device between {@code ACTIVE} and {@code DEACTIVATED} (docs/main/CYCLES-PLAN.md §8's
     * pinned contract).
     *
     * <p>Idempotent. {@code DEACTIVATED} on an already-{@code DELETED} device restores it —
     * recovering a source never silently resumes streaming from it, so activating afterward is a
     * second, deliberate step. Requesting {@code ACTIVE} on a deleted device is refused (409):
     * restore first. Finer-grained than moving the whole asset: one broken camera on a drone that
     * is otherwise flying can be silenced on its own.
     *
     * @param id      the device to move
     * @param request the state to move it to; {@code ACTIVE} or {@code DEACTIVATED}
     * @return the device in its new state
     */
    @PostMapping("/{id}/state")
    public DeviceResponse setState(@PathVariable String id, @RequestBody SetLifecycleStateRequest request) {
        DeviceId deviceId = DeviceId.of(id);
        var state = request.toLifecycleState(); // an unrecognized state is a 400, before the scope 404
        streamAccess.requireVisible(deviceId);
        Device updated = deviceService.setState(deviceId, state, currentUser.userId());
        return DeviceResponse.from(updated);
    }

    /**
     * Removes a source from service and from view — a soft delete.
     *
     * <p>The stream stops and the device disappears from listings, but the record survives, so
     * the usages and telemetry it produced stay attributable and the removal can be undone by
     * {@link #setState}'s restore semantics. It also remains a member of its asset, which is why
     * removing an asset's <em>last</em> source needs no special case and is never refused.
     *
     * @param id the device to delete
     * @return the device in its deleted state
     */
    @DeleteMapping("/{id}")
    public DeviceResponse delete(@PathVariable String id) {
        DeviceId deviceId = DeviceId.of(id);
        requireManageOrg();
        return DeviceResponse.from(deviceService.delete(deviceId, currentUser.userId()));
    }

    /**
     * Guards {@link #register}/{@link #delete}: fleet-administration actions over the device
     * inventory itself, gated on org-level management authority rather than on any one group's
     * ownership (see class javadoc for why a device has no group of its own to check).
     */
    private void requireManageOrg() {
        if (!currentUser.scope().canManageOrg()) {
            throw new AccessDeniedException("Not permitted to manage devices");
        }
    }
}

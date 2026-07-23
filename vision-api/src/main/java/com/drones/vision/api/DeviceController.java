package com.drones.vision.api;

import com.drones.vision.api.dto.DeviceResponse;
import com.drones.vision.api.dto.RegisterDeviceRequest;
import com.drones.vision.api.dto.SetLifecycleStateRequest;
import com.drones.vision.api.dto.UpdateDeviceRequest;
import com.drones.vision.application.DeviceService;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
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

/**
 * Driving REST adapter for the life of a device: registering, listing,
 * editing, taking out of service, and deleting.
 *
 * <p>Constructor-injected with {@link DeviceService} and {@link CurrentUser} only. Per the
 * hexagonal dependency rule (ARCHITECTURE.md §2, enforced by ArchUnit), this module depends only
 * on {@code vision-domain} and {@code vision-application} — never on an adapter.
 *
 * <h2>Who the change is attributed to</h2>
 * The acting user comes from {@link CurrentUser} and is passed to every mutating call, so the
 * audit trail records a principal without {@link DeviceService} knowing how it was authenticated.
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

    public DeviceController(DeviceService deviceService, CurrentUser currentUser) {
        this.deviceService = Objects.requireNonNull(deviceService, "deviceService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
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
        Device device = deviceService.register(request.toRegistration(), currentUser.userId());
        return DeviceResponse.from(device);
    }

    /**
     * Lists registered devices.
     *
     * @param includeDeleted whether to include soft-deleted devices; excluded by default, so
     *                       "deleted" behaves as deleted unless a view explicitly asks otherwise
     * @return the currently registered devices
     */
    @GetMapping
    public List<DeviceResponse> list(@RequestParam(defaultValue = "false") boolean includeDeleted) {
        return deviceService.devices(includeDeleted).stream().map(DeviceResponse::from).toList();
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
        Device updated = deviceService.update(DeviceId.of(id),
                (request == null ? UpdateDeviceRequest.EMPTY : request).toEdit(), currentUser.userId());
        return DeviceResponse.from(updated);
    }

    /**
     * Moves a device between {@code ACTIVE} and {@code DEACTIVATED} (docs/CYCLES-PLAN.md §8's
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
        Device updated = deviceService.setState(DeviceId.of(id), request.toLifecycleState(), currentUser.userId());
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
        return DeviceResponse.from(deviceService.delete(DeviceId.of(id), currentUser.userId()));
    }
}

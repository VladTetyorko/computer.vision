package com.drones.vision.application.device;

import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.LifecycleState;
import com.drones.vision.domain.model.UserId;

import java.util.List;
import java.util.Optional;
import com.drones.vision.application.asset.AssetService;

/**
 * Everything the application does with devices — the sources frames and telemetry come from.
 *
 * <p>One interface, one implementation ({@link DefaultDeviceService}). The seam exists so
 * controllers and {@link AssetService} can be tested against a stub, not because a second
 * implementation is expected.
 *
 * <p>The acting user is a method parameter, never a constructor dependency: it varies per request,
 * and threading it through construction is what turns services into eight-argument classes. The
 * API edge resolves it from the token and passes it down.
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use; all shared state lives behind driven ports.
 */
public interface DeviceService {

    /**
     * Registers a new device, in service.
     *
     * @param registration what to register
     * @param actor        the user performing the registration, for the audit trail
     * @return the registered device
     */
    Device register(DeviceRegistration registration, UserId actor);

    /**
     * Lists devices that have not been soft-deleted.
     *
     * @return an immutable snapshot
     */
    default List<Device> devices() {
        return devices(false);
    }

    /**
     * Lists devices, optionally including soft-deleted ones.
     *
     * <p>Deleted devices are excluded by default so "deleted" behaves as deleted everywhere; the
     * flag exists for views whose whole job is showing what was removed.
     *
     * @param includeDeleted whether to include devices in {@link LifecycleState#DELETED}
     * @return an immutable snapshot
     */
    List<Device> devices(boolean includeDeleted);

    /**
     * Finds one device by id, deleted or not.
     *
     * @param id the device id
     * @return the device, or {@link Optional#empty()} if none exists
     */
    Optional<Device> find(DeviceId id);

    /**
     * Applies a partial edit.
     *
     * <p>A running stream keeps its current settings; the edit takes effect on the next start,
     * because reconnecting a live stream underneath a viewer unasked is worse than an explicit
     * restart.
     *
     * @param id    the device to edit
     * @param edit  the fields to change
     * @param actor the user performing the edit
     * @return the updated device
     * @throws java.util.NoSuchElementException if no device has that id
     */
    Device update(DeviceId id, DeviceEdit edit, UserId actor);

    /**
     * Moves a device to a lifecycle state, stopping its stream when it leaves service.
     *
     * <p>Idempotent. A soft-deleted device may be restored to {@link LifecycleState#DEACTIVATED}
     * but not straight to {@link LifecycleState#ACTIVE}, so recovering a source never silently
     * resumes streaming from it.
     *
     * @param id    the device to move
     * @param state the state to move it to
     * @param actor the user performing the change
     * @return the device as persisted
     * @throws java.util.NoSuchElementException if no device has that id
     * @throws IllegalStateException            if activating a device that is currently deleted
     */
    Device setState(DeviceId id, LifecycleState state, UserId actor);

    /**
     * Soft-deletes a device: stops its stream, hides it, keeps the record.
     *
     * <p>It stays a member of its asset, which is why removing an asset's last source needs no
     * special case and is never refused.
     *
     * @param id    the device to delete
     * @param actor the user performing the deletion
     * @return the device in its deleted state
     * @throws java.util.NoSuchElementException if no device has that id
     */
    Device delete(DeviceId id, UserId actor);
}

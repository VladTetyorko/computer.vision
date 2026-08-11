package com.drones.vision.warehouse.domain.model;

import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.LifecycleState;
import com.drones.vision.kernel.StreamDescriptor;
import java.util.Set;

/**
 * A registered source of video and/or telemetry: a drone, camera, or robot.
 *
 * <p>{@code capabilities} is defensively copied to an immutable set so a
 * {@code Device} instance can never be mutated after construction; shared
 * device state is only ever reached through {@code DeviceRepositoryPort},
 * never held directly by pipeline code.
 *
 * @param id           typed device identity
 * @param name         human-readable device name; must not be blank
 * @param capabilities features this device exposes; defensively copied to an immutable set
 * @param stream       how to obtain this device's video stream
 * @param state        whether the device is in service; a deactivated device refuses to stream
 */
public record Device(DeviceId id, String name, Set<Capability> capabilities, StreamDescriptor stream,
                      LifecycleState state) {

    public Device {
        if (id == null) {
            throw new IllegalArgumentException("Device id must not be null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Device name must not be blank");
        }
        if (capabilities == null) {
            throw new IllegalArgumentException("Device capabilities must not be null");
        }
        if (stream == null) {
            throw new IllegalArgumentException("Device stream must not be null");
        }
        if (state == null) {
            throw new IllegalArgumentException("Device state must not be null");
        }
        capabilities = Set.copyOf(capabilities);
    }

    /**
     * Creates a device in service.
     *
     * <p>Newly registered devices are always {@link LifecycleState#ACTIVE}; deactivation is an
     * explicit later act, never an accident of construction.
     */
    public Device(DeviceId id, String name, Set<Capability> capabilities, StreamDescriptor stream) {
        this(id, name, capabilities, stream, LifecycleState.ACTIVE);
    }

    /**
     * Whether this device is in service.
     *
     * @return {@code true} when {@link #state()} is {@link LifecycleState#ACTIVE}
     */
    public boolean isActive() {
        return state == LifecycleState.ACTIVE;
    }

    /**
     * Whether this device has been soft-deleted.
     *
     * @return {@code true} when {@link #state()} is {@link LifecycleState#DELETED}
     */
    public boolean isDeleted() {
        return state == LifecycleState.DELETED;
    }

    /**
     * Returns a copy of this device with edited descriptive fields.
     *
     * <p>Identity and lifecycle state are not editable here — re-pointing a device at a new URI
     * is an edit, taking it out of service is not.
     *
     * @param name         the replacement name; must not be blank
     * @param capabilities the replacement capability set; defensively copied
     * @param stream       the replacement stream descriptor
     * @return a new {@code Device} with those fields replaced
     */
    public Device withDetails(String name, Set<Capability> capabilities, StreamDescriptor stream) {
        return new Device(id, name, capabilities, stream, state);
    }

    /**
     * Returns a copy of this device in a different lifecycle state.
     *
     * @param state the replacement state
     * @return a new {@code Device} with {@code state} replaced
     */
    public Device withState(LifecycleState state) {
        return new Device(id, name, capabilities, stream, state);
    }
}

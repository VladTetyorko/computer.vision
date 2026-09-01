package com.drones.vision.warehouse.application.device;

import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.DeviceOrigin;
import com.drones.vision.kernel.StreamDescriptor;

import java.util.Set;

/**
 * Everything needed to register a new device.
 *
 * <p>A top-level record rather than a type nested in {@link DeviceService}, so callers can name
 * their input without importing the service, and so the wire DTO in {@code …api.dto} maps to one
 * plain value.
 *
 * @param name         human-readable device name; must not be blank
 * @param capabilities features the device exposes; defensively copied, must not be empty
 * @param stream       how to obtain the device's stream
 * @param origin       whether the registered device is real or synthetic
 */
public record DeviceRegistration(String name, Set<Capability> capabilities, StreamDescriptor stream,
                                  DeviceOrigin origin) {

    public DeviceRegistration {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("DeviceRegistration name must not be blank");
        }
        if (capabilities == null || capabilities.isEmpty()) {
            throw new IllegalArgumentException("DeviceRegistration capabilities must not be empty");
        }
        if (stream == null) {
            throw new IllegalArgumentException("DeviceRegistration stream must not be null");
        }
        if (origin == null) {
            throw new IllegalArgumentException("DeviceRegistration origin must not be null");
        }
        capabilities = Set.copyOf(capabilities);
    }

    /**
     * Convenience constructor defaulting {@link #origin()} to {@link DeviceOrigin#LIVE} — kept so
     * every pre-existing call site (registering a real device, overwhelmingly the common case)
     * still compiles unchanged; at most this one overload, no chain
     * (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R1/R4).
     */
    public DeviceRegistration(String name, Set<Capability> capabilities, StreamDescriptor stream) {
        this(name, capabilities, stream, DeviceOrigin.LIVE);
    }
}

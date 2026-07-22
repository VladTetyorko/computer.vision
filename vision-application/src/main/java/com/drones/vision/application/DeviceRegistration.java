package com.drones.vision.application;

import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.StreamDescriptor;

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
 */
public record DeviceRegistration(String name, Set<Capability> capabilities, StreamDescriptor stream) {

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
        capabilities = Set.copyOf(capabilities);
    }
}

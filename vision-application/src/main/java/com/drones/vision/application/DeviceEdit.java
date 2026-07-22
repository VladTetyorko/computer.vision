package com.drones.vision.application;

import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.StreamDescriptor;

import java.util.Set;

/**
 * A partial edit to a device: every field is optional, {@code null} means "leave unchanged".
 *
 * <p>Partial rather than whole-record replacement, so a caller that only knows about the field it
 * wants to change cannot blank out fields it never sent.
 *
 * @param name         replacement name, or {@code null} to keep the current one
 * @param capabilities replacement capabilities, or {@code null} to keep the current set
 * @param stream       replacement stream descriptor, or {@code null} to keep the current one
 */
public record DeviceEdit(String name, Set<Capability> capabilities, StreamDescriptor stream) {

    /** An edit that changes nothing — the identity of this operation. */
    public static final DeviceEdit NOTHING = new DeviceEdit(null, null, null);

    public DeviceEdit {
        if (name != null && name.isBlank()) {
            throw new IllegalArgumentException("DeviceEdit name must not be blank");
        }
        if (capabilities != null) {
            if (capabilities.isEmpty()) {
                throw new IllegalArgumentException("DeviceEdit capabilities must not be empty");
            }
            capabilities = Set.copyOf(capabilities);
        }
    }
}

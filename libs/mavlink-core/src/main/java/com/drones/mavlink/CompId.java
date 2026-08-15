package com.drones.mavlink;

/**
 * A MAVLink component id — one software/hardware component within a system (autopilot, camera,
 * gimbal, a ground control station's own commanding component, ...). Valid range is 1..255 per
 * the wire format, same rationale as {@link SysId}.
 */
public record CompId(int value) {

    public CompId {
        if (value < 1 || value > 255) {
            throw new IllegalArgumentException("CompId must be 1..255, got " + value);
        }
    }
}

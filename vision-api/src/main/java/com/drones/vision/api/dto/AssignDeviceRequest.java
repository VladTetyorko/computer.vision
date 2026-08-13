package com.drones.vision.api.dto;

import com.drones.vision.kernel.DeviceId;

/**
 * Request body for {@code POST /api/assets/{id}/devices} (docs/main/CYCLES-PLAN.md §8's pinned
 * contract) — assigns an existing, unowned device to an asset.
 *
 * @param deviceId the device to assign, as a canonical UUID string
 */
public record AssignDeviceRequest(String deviceId) {

    /**
     * Parses {@link #deviceId()} into a {@link DeviceId}.
     *
     * @return the parsed device id
     * @throws IllegalArgumentException if {@link #deviceId()} is blank or not a valid UUID
     */
    public DeviceId toDeviceId() {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId must not be blank");
        }
        return DeviceId.of(deviceId);
    }
}

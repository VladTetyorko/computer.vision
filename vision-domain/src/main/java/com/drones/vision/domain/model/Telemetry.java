package com.drones.vision.domain.model;

import java.time.Instant;
import java.util.Map;

/**
 * A telemetry sample from a device: position, attitude, and battery state.
 *
 * <p>All value fields except {@code deviceId} and {@code at} are nullable —
 * not every device reports every field (e.g. a fixed IP camera has no GPS),
 * so a missing reading is modeled as {@code null} rather than a sentinel
 * value. {@code extra} carries device-specific readings not covered by the
 * named fields (e.g. RSSI) and is defensively copied to an immutable map.
 *
 * @param deviceId         device this sample came from
 * @param at               sample timestamp
 * @param latitude         degrees, nullable
 * @param longitude        degrees, nullable
 * @param altitudeMeters   meters, nullable
 * @param headingDegrees   degrees, nullable
 * @param batteryPercent   percent, nullable
 * @param extra            device-specific readings; defensively copied to an immutable map
 */
public record Telemetry(DeviceId deviceId, Instant at, Double latitude, Double longitude, Double altitudeMeters,
                         Double headingDegrees, Double batteryPercent, Map<String, Double> extra) {

    public Telemetry {
        if (deviceId == null) {
            throw new IllegalArgumentException("Telemetry deviceId must not be null");
        }
        if (at == null) {
            throw new IllegalArgumentException("Telemetry at must not be null");
        }
        if (extra == null) {
            throw new IllegalArgumentException("Telemetry extra must not be null");
        }
        extra = Map.copyOf(extra);
    }
}

package com.drones.vision.kernel;

import java.time.Instant;
import java.util.Map;

/**
 * A telemetry sample from a device: position, attitude, battery state, and (docs/plans/done/FC-INTEGRATIONS-PLAN.md
 * F-a) flight-controller-reported state.
 *
 * <p><b>Kernel, not flight-owned</b> (docs/plans/active/DOMAIN-SEPARATION-W1.md §15, W1.6c): a pure
 * record naming only {@link DeviceId} (already kernel) and {@link FlightState}, with no ports and no
 * aggregate references — the same standing as {@link GeoPosition}/{@link BoundingBox}. Five contexts
 * read it (flight, perception's OSD, warehouse's stats, events' replay, map), which is precisely why
 * it belongs where every context can already see it rather than behind a flight-owned wall. Revisit
 * if W2's wire DTOs make per-context divergence real (docs/plans/active/DOMAIN-SEPARATION-PLAN.md §9).
 *
 * <p>All value fields except {@code deviceId} and {@code at} are nullable —
 * not every device reports every field (e.g. a fixed IP camera has no GPS),
 * so a missing reading is modeled as {@code null} rather than a sentinel
 * value. {@code extra} carries device-specific readings not covered by the
 * named fields (e.g. RSSI) and is defensively copied to an immutable map.
 *
 * <p>{@code flightState} is {@code null} until a decoder has something to report (e.g. a MAVLink
 * source before its first {@code HEARTBEAT}, or any non-flight-controller device); the 8-arg
 * convenience constructor defaults it to {@code null} so every pre-existing call site compiles
 * unchanged (same "N-1-arg convenience ctor" idiom as {@code AssetUsage}'s 7-arg ctor).
 *
 * @param deviceId         device this sample came from
 * @param at               sample timestamp
 * @param latitude         degrees, nullable
 * @param longitude        degrees, nullable
 * @param altitudeMeters   meters, nullable
 * @param headingDegrees   degrees, nullable
 * @param batteryPercent   percent, nullable
 * @param extra            device-specific readings; defensively copied to an immutable map
 * @param flightState      flight-controller-reported state, or {@code null} if none is known yet
 */
public record Telemetry(DeviceId deviceId, Instant at, Double latitude, Double longitude, Double altitudeMeters,
                         Double headingDegrees, Double batteryPercent, Map<String, Double> extra,
                         FlightState flightState) {

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

    /**
     * Convenience constructor for callers that don't have flight-controller state to report —
     * defaults {@link #flightState()} to {@code null}, unchanged behavior for every pre-existing
     * call site.
     */
    public Telemetry(DeviceId deviceId, Instant at, Double latitude, Double longitude, Double altitudeMeters,
                      Double headingDegrees, Double batteryPercent, Map<String, Double> extra) {
        this(deviceId, at, latitude, longitude, altitudeMeters, headingDegrees, batteryPercent, extra, null);
    }
}

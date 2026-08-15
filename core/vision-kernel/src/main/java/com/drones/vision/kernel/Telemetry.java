package com.drones.vision.kernel;

import java.time.Instant;
import java.util.Map;

/**
 * A telemetry sample from a device: position, attitude, battery state, and (docs/plans/done/FC-INTEGRATIONS-PLAN.md
 * F-a) flight-controller-reported state.
 *
 * <p><b>Kernel, not flight-owned</b> (docs/plans/active/DOMAIN-SEPARATION-W1.md §15, W1.6c): a pure
 * record naming only {@link DeviceId} (already kernel), {@link FlightState} and {@link Attitude}, with
 * no ports and no aggregate references — the same standing as {@link GeoPosition}/{@link BoundingBox}.
 * Five contexts read it (flight, perception's OSD, warehouse's stats, events' replay, map), which is
 * precisely why it belongs where every context can already see it rather than behind a flight-owned
 * wall. Revisit if W2's wire DTOs make per-context divergence real
 * (docs/plans/active/DOMAIN-SEPARATION-PLAN.md §9).
 *
 * <p>All value fields except {@code deviceId} and {@code at} are nullable —
 * not every device reports every field (e.g. a fixed IP camera has no GPS),
 * so a missing reading is modeled as {@code null} rather than a sentinel
 * value. {@code extra} carries device-specific readings not covered by the
 * named fields (e.g. RSSI) and is defensively copied to an immutable map.
 *
 * <p><b>{@code altitudeMeters} is AMSL</b> (above mean sea level, e.g. MAVLink {@code
 * GLOBAL_POSITION_INT.alt}) — {@link GeoPosition} and every other consumer in the codebase already
 * read it that way (docs/plans/active/GEO-POSE-PLAN.md G1), so this field keeps that meaning rather
 * than being silently redefined. {@code aglMeters} is the separate, new field for height above the
 * ground (MAVLink {@code GLOBAL_POSITION_INT.relative_alt}) — the figure a projection actually wants,
 * since a projection's "how far away does the camera's line of sight hit the ground" question is
 * answered by height above the ground, not height above the sea.
 *
 * <p>{@code flightState} is {@code null} until a decoder has something to report (e.g. a MAVLink
 * source before its first {@code HEARTBEAT}, or any non-flight-controller device); the 8-arg
 * convenience constructor defaults it to {@code null} so every pre-existing call site compiles
 * unchanged (same "N-1-arg convenience ctor" idiom as {@code AssetUsage}'s 7-arg ctor).
 *
 * <p>{@code aglMeters}, {@code attitude} and {@code deviceBootMillis} (docs/plans/active/GEO-POSE-PLAN.md
 * §4.1, wave V1) are appended the same way: the 9-arg constructor (the previous full arity, itself a
 * convenience overload since W1.6c) defaults all three to {@code null}, so all 46 pre-existing
 * {@code new Telemetry(...)} call sites across both the 8-arg and 9-arg shapes keep compiling
 * unchanged. {@code deviceBootMillis} is the flight controller's own free-running boot clock ({@code
 * GLOBAL_POSITION_INT.time_boot_ms}) — carried so a future wave can align a frame to the pose that was
 * current when it was captured; nothing consumes it yet ({@code Telemetry.at} stays wall-clock-at-decode).
 *
 * @param deviceId         device this sample came from
 * @param at               sample timestamp
 * @param latitude         degrees, nullable
 * @param longitude        degrees, nullable
 * @param altitudeMeters   meters, AMSL (above mean sea level), nullable
 * @param headingDegrees   degrees, nullable
 * @param batteryPercent   percent, nullable
 * @param extra            device-specific readings; defensively copied to an immutable map
 * @param flightState      flight-controller-reported state, or {@code null} if none is known yet
 * @param aglMeters        meters, height above the ground (not sea level), nullable; {@code null}
 *                         until a decoder has a relative-altitude reading to report
 * @param attitude         aircraft/gimbal orientation, or {@code null} if none is known yet
 * @param deviceBootMillis milliseconds since the reporting device booted, or {@code null} if unknown;
 *                         not wall-clock time, and not comparable across devices
 */
public record Telemetry(DeviceId deviceId, Instant at, Double latitude, Double longitude, Double altitudeMeters,
                         Double headingDegrees, Double batteryPercent, Map<String, Double> extra,
                         FlightState flightState, Double aglMeters, Attitude attitude, Long deviceBootMillis) {

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
        if (aglMeters != null && (Double.isNaN(aglMeters) || Double.isInfinite(aglMeters))) {
            throw new IllegalArgumentException("Telemetry aglMeters must be finite: " + aglMeters);
        }
        if (deviceBootMillis != null && deviceBootMillis < 0) {
            throw new IllegalArgumentException(
                    "Telemetry deviceBootMillis must not be negative: " + deviceBootMillis);
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

    /**
     * Convenience constructor for callers that don't have AGL, attitude or a device boot timestamp
     * to report — defaults {@link #aglMeters()}, {@link #attitude()} and {@link #deviceBootMillis()}
     * all to {@code null}, unchanged behavior for every pre-existing call site (this was the full
     * arity before docs/plans/active/GEO-POSE-PLAN.md wave V1 appended the three new components).
     */
    public Telemetry(DeviceId deviceId, Instant at, Double latitude, Double longitude, Double altitudeMeters,
                      Double headingDegrees, Double batteryPercent, Map<String, Double> extra,
                      FlightState flightState) {
        this(deviceId, at, latitude, longitude, altitudeMeters, headingDegrees, batteryPercent, extra, flightState,
                null, null, null);
    }
}

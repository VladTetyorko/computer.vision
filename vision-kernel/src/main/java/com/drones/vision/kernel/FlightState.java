package com.drones.vision.kernel;

import java.util.List;

/**
 * Flight-controller-reported state, decoded from ArduPilot/INAV/Betaflight/PX4 telemetry
 * (docs/plans/done/FC-INTEGRATIONS-PLAN.md) — arm state, active mode, failsafe, GPS fix quality, RSSI, and
 * any known arming blockers.
 *
 * <p><b>Kernel, not flight-owned</b> (docs/plans/active/DOMAIN-SEPARATION-W1.md §15, W1.6c): a pure
 * record naming nothing at all outside {@code java.util}, read by every context that reads {@link
 * Telemetry} — the same standing as {@link GeoPosition}/{@link BoundingBox}. Revisit if W2's wire
 * DTOs make per-context divergence real (docs/plans/active/DOMAIN-SEPARATION-PLAN.md §9).
 *
 * <p>Rides {@link Telemetry} as its nullable 9th component: a device with
 * no flight-controller telemetry (a camera, a legacy sample, or a MAVLink stream that hasn't
 * received a {@code HEARTBEAT} yet) simply carries {@code null} here, same "honest null over fake
 * reading" discipline as every other optional {@link Telemetry} field.
 *
 * <p>Every field except {@link #armingBlockers()} is individually nullable — a decoder merges
 * this record incrementally as different MAVLink messages arrive (a GPS fix without a heartbeat
 * yet, an armed flag without a satellite count, etc.), so "unknown" and "known false/zero" must
 * stay distinguishable field by field, not collapsed to one record-level null. {@link
 * #armingBlockers()} is the one exception: non-null and defensively copied, empty meaning "none
 * currently known" rather than "unknown" (a genuinely empty list is the common case once armed).
 *
 * @param firmware        {@code "ardupilot"}, {@code "generic"} (Betaflight/INAV-as-generic),
 *                         {@code "px4"}, or {@code null} if not yet determined
 * @param mode             human-readable flight mode name (e.g. {@code "RTL"}, {@code "Loiter"},
 *                         {@code "Angle"}), or {@code null} if not yet known
 * @param armed            {@code true}/{@code false} once known, {@code null} before the first
 *                         {@code HEARTBEAT}
 * @param failsafe         {@code true} while the flight controller reports a critical system
 *                         state, {@code null} before the first {@code HEARTBEAT}
 * @param gpsFixType       GPS fix quality, the MAVLink {@code GPS_FIX_TYPE} ordinal (0=no GPS ..
 *                         8=PPP), range [0,8] if present, {@code null} if unknown
 * @param satellites       number of GPS satellites visible, must not be negative if present,
 *                         {@code null} if unknown
 * @param hdop             horizontal dilution of precision, must not be negative if present,
 *                         {@code null} if unknown
 * @param rssiPercent      RC link signal strength as a percent, range [0,100] if present,
 *                         {@code null} if unknown
 * @param armingBlockers   known reasons the aircraft cannot currently arm (ArduPilot {@code
 *                         PreArm}/{@code Arm} {@code STATUSTEXT} reasons); non-null, defensively
 *                         copied, empty meaning "none currently known"
 */
public record FlightState(String firmware, String mode, Boolean armed, Boolean failsafe, Integer gpsFixType,
                           Integer satellites, Double hdop, Integer rssiPercent, List<String> armingBlockers) {

    public FlightState {
        if (gpsFixType != null && (gpsFixType < 0 || gpsFixType > 8)) {
            throw new IllegalArgumentException("FlightState gpsFixType must be within [0,8]: " + gpsFixType);
        }
        if (satellites != null && satellites < 0) {
            throw new IllegalArgumentException("FlightState satellites must not be negative: " + satellites);
        }
        if (hdop != null && hdop < 0) {
            throw new IllegalArgumentException("FlightState hdop must not be negative: " + hdop);
        }
        if (rssiPercent != null && (rssiPercent < 0 || rssiPercent > 100)) {
            throw new IllegalArgumentException("FlightState rssiPercent must be within [0,100]: " + rssiPercent);
        }
        if (armingBlockers == null) {
            throw new IllegalArgumentException("FlightState armingBlockers must not be null");
        }
        armingBlockers = List.copyOf(armingBlockers);
    }

    /**
     * @return a {@code FlightState} with every field unknown/null and no known arming blockers —
     *         the starting point a decoder merges {@code HEARTBEAT}/{@code GPS_RAW_INT}/etc. into
     */
    public static FlightState empty() {
        return new FlightState(null, null, null, null, null, null, null, null, List.of());
    }
}

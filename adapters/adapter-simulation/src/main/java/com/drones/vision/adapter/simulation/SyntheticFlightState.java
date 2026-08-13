package com.drones.vision.adapter.simulation;

import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.FlightState;

import java.util.List;
import java.util.Random;

/**
 * Pure, framework-free synthetic {@link FlightState} generator for one {@code
 * SimulatedTelemetrySource} device runtime (docs/plans/done/FC-INTEGRATIONS-PLAN.md F-c) — extracted from
 * {@code SimulatedTelemetrySource.DeviceRuntime}, mirroring the {@link RoutePlan} precedent: a
 * scheduler-free engine that is a plain function of tick index and battery percent, so the whole
 * startup-ramp / nominal-cruise / battery-driven-RTL-Land sequence is unit-testable without a
 * running scheduler.
 *
 * <p>For the first {@value #STARTUP_DISARMED_TICKS} ticks of an instance's life the synthetic
 * aircraft looks like it's still on the ground: {@code armed=false}, one arming blocker, and
 * {@code gpsFixType} ramping {@code 1 → 3}. After that it flies nominally ({@link
 * #FLIGHT_STATE_FIRMWARE}, {@link #FLIGHT_MODE_LOITER}, armed, no failsafe, 3D fix, mildly jittered
 * satellites/hdop/rssi) until the caller-supplied {@code batteryPercent} crosses a threshold: below
 * {@value #RTL_BATTERY_PERCENT_THRESHOLD}% the mode switches to {@link #FLIGHT_MODE_RTL} with
 * {@code failsafe=true}; below {@value #LAND_BATTERY_PERCENT_THRESHOLD}% it switches to {@link
 * #FLIGHT_MODE_LAND} (failsafe stays true) — battery-driven RTL/Land drama for dev demos, gated
 * behind having armed (an aircraft can't RTL/Land while disarmed on the ground).
 *
 * <p>The jitter (and therefore the whole per-tick sequence, since it is otherwise a pure function
 * of tick index and battery percent) is seeded per device via {@link #forDevice(DeviceId)}, so two
 * instances built for the same device id reproduce an identical {@link FlightState} sequence
 * tick-for-tick.
 */
final class SyntheticFlightState {

    /** Number of leading samples per subscription where the synthetic aircraft is still disarmed. */
    static final long STARTUP_DISARMED_TICKS = 5L;
    /** Below this drained battery percent (exclusive), the synthetic mode switches to RTL + failsafe. */
    static final double RTL_BATTERY_PERCENT_THRESHOLD = 20.0;
    /** Below this drained battery percent (exclusive), the synthetic mode switches to Land (still failsafe). */
    static final double LAND_BATTERY_PERCENT_THRESHOLD = 8.0;

    static final String FLIGHT_STATE_FIRMWARE = "ardupilot";
    static final String FLIGHT_MODE_LOITER = "Loiter";
    static final String FLIGHT_MODE_RTL = "RTL";
    static final String FLIGHT_MODE_LAND = "Land";
    static final String STARTUP_ARMING_BLOCKER = "PreArm: GPS: waiting for home";
    static final int NOMINAL_SATELLITES = 12;
    static final double NOMINAL_HDOP = 0.8;
    static final int NOMINAL_RSSI_PERCENT = 90;

    /**
     * Drives the mild jitter (satellites/hdop/rssi); seeded from a hash of a device id so the whole
     * per-tick {@link FlightState} sequence is reproducible for that device. An instance is touched
     * only by the single scheduler thread of the {@code DeviceRuntime} that owns it.
     */
    private final Random jitter;

    private SyntheticFlightState(long seed) {
        this.jitter = new Random(seed);
    }

    /**
     * @param deviceId the device this generator serves; the same id always yields the same jitter
     *                 sequence (see class javadoc)
     * @return a fresh generator seeded deterministically from {@code deviceId}
     */
    static SyntheticFlightState forDevice(DeviceId deviceId) {
        return new SyntheticFlightState(
                deviceId.value().getMostSignificantBits() ^ deviceId.value().getLeastSignificantBits());
    }

    /**
     * Synthetic {@link FlightState} for tick {@code n}: disarmed with a ramping GPS fix and one
     * arming blocker for the first {@value #STARTUP_DISARMED_TICKS} ticks (startup realism), then a
     * nominal armed Loiter state — unless {@code batteryPercent} (the caller's already-drained
     * value) has crossed the RTL/Land thresholds, gating the "battery-driven drama" behind having
     * armed in the first place.
     *
     * @param n              tick index since this generator's owning runtime started (0-based)
     * @param batteryPercent the drained battery percent for this same tick
     */
    FlightState at(long n, double batteryPercent) {
        int satellites = Math.max(0, NOMINAL_SATELLITES + jitterInt(1));
        double hdop = Math.max(0.0, NOMINAL_HDOP + jitterDouble(0.1));
        int rssiPercent = clampPercent(NOMINAL_RSSI_PERCENT + jitterInt(3));

        if (n < STARTUP_DISARMED_TICKS) {
            int gpsFixType = (int) Math.min(3L, 1L + n);
            return new FlightState(FLIGHT_STATE_FIRMWARE, FLIGHT_MODE_LOITER, false, false, gpsFixType,
                    satellites, hdop, rssiPercent, List.of(STARTUP_ARMING_BLOCKER));
        }
        if (batteryPercent < LAND_BATTERY_PERCENT_THRESHOLD) {
            return new FlightState(FLIGHT_STATE_FIRMWARE, FLIGHT_MODE_LAND, true, true, 3,
                    satellites, hdop, rssiPercent, List.of());
        }
        if (batteryPercent < RTL_BATTERY_PERCENT_THRESHOLD) {
            return new FlightState(FLIGHT_STATE_FIRMWARE, FLIGHT_MODE_RTL, true, true, 3,
                    satellites, hdop, rssiPercent, List.of());
        }
        return new FlightState(FLIGHT_STATE_FIRMWARE, FLIGHT_MODE_LOITER, true, false, 3,
                satellites, hdop, rssiPercent, List.of());
    }

    /** Deterministic (seeded) integer jitter in {@code [-magnitude, magnitude]}; 0 if magnitude &lt;= 0. */
    private int jitterInt(int magnitude) {
        return magnitude <= 0 ? 0 : jitter.nextInt(2 * magnitude + 1) - magnitude;
    }

    /** Deterministic (seeded) double jitter in {@code [-magnitude, magnitude]}; 0 if magnitude &lt;= 0. */
    private double jitterDouble(double magnitude) {
        return magnitude <= 0 ? 0.0 : (jitter.nextDouble() * 2 - 1) * magnitude;
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }
}

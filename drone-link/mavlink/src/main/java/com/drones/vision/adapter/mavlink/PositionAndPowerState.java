package com.drones.vision.adapter.mavlink;

import io.dronefleet.mavlink.common.GlobalPositionInt;

/**
 * {@link MavlinkTelemetryDecoder}'s accumulated position/velocity/battery fields —
 * {@code GLOBAL_POSITION_INT} and {@code SYS_STATUS}/{@code BATTERY_STATUS} — the subset that maps
 * onto {@link com.drones.vision.kernel.Telemetry}'s own named fields plus the velocity/
 * battery-voltage {@code extra} keys (docs/plans/active/LAYERING-REFACTOR-PLAN.md E2 split out of {@code
 * MavlinkTelemetryDecoder}'s original 35-field flat state). See {@code MavlinkTelemetryDecoder}'s
 * own javadoc for the full unit-conversion table.
 *
 * <p>docs/plans/done/GEO-POSE-PLAN.md wave V2 added {@code GLOBAL_POSITION_INT}'s own {@code
 * relative_alt} (height above home, mm) → {@link com.drones.vision.kernel.Telemetry#aglMeters()}
 * (÷ 1000, no sentinel — always sent) and {@code time_boot_ms} → {@link
 * com.drones.vision.kernel.Telemetry#deviceBootMillis()} (already a non-negative {@code long} once
 * dronefleet widens the wire {@code uint32_t}, so no range check is needed here) alongside this
 * message's pre-existing {@code lat}/{@code lon}/{@code alt}/{@code hdg}/{@code vx,vy,vz} mappings.
 *
 * <p>Package-private mutable struct, not a record: every field starts {@code null} ("not yet
 * known") and is merged in place as messages arrive, exactly like the flat-field version this
 * replaces. One instance per {@link MavlinkTelemetryDecoder}, touched only from that decoder's own
 * caller thread (the owning {@code MavlinkGateway}'s single dispatcher-callback thread) — no
 * locking of its own.
 * Fields are package-private with no accessors: {@link MavlinkTelemetryDecoder} is this type's only
 * collaborator.
 *
 * <p><b>{@code latitude}/{@code longitude} require a GPS fix (docs/plans/active/OPERATOR-UX-4-PLAN.md
 * N1).</b> {@link #applyPosition} records {@code GLOBAL_POSITION_INT}'s {@code lat}/{@code lon}
 * only when the decoder's current {@code GPS_RAW_INT}-reported fix is at least 2D; otherwise both
 * fields are set to {@code null} — a real ESP32 rover with no GPS fix sends {@code lat=lon=0}
 * unconditionally, and Null Island is not a legal vehicle position for this product. Every other
 * field this message carries (altitude, heading, velocity, AGL, boot millis) is unaffected — it is
 * only the position pair that is gated.
 */
final class PositionAndPowerState {

    private static final int UNKNOWN_HEADING_CENTIDEGREES = 65535;
    private static final int UNKNOWN_BATTERY_PERCENT = -1;
    private static final int UNKNOWN_VOLTAGE_BATTERY_MILLIVOLTS = 65535;

    Double latitude;
    Double longitude;
    Double altitudeMeters;
    Double headingDegrees;
    Double batteryPercent;
    Double vxMps;
    Double vyMps;
    Double vzMps;
    Double batteryVoltage;
    Double aglMeters;
    Long deviceBootMillis;

    /**
     * @param hasGpsFix {@link FlightStatusState#hasGpsFix()} at the moment this message arrived —
     *                  {@code lat}/{@code lon} are recorded only when {@code true}
     *                  (docs/plans/active/OPERATOR-UX-4-PLAN.md N1); when {@code false}, {@link
     *                  #latitude}/{@link #longitude} are set to {@code null}, dropping any
     *                  previously-known position rather than leaving a now-stale reading in place —
     *                  a vehicle whose fix drops mid-session must stop reporting a position, not
     *                  freeze on its last one. Every other field on this message ({@code alt},
     *                  {@code hdg}, velocity, {@code relative_alt}, {@code time_boot_ms}) is a
     *                  reading independent of the GPS fix and is always recorded.
     */
    void applyPosition(GlobalPositionInt position, boolean hasGpsFix) {
        if (hasGpsFix) {
            latitude = position.lat() / 1e7;
            longitude = position.lon() / 1e7;
        } else {
            // No fix (or none seen yet) -- Null Island is not a legal vehicle position for this
            // product; never persist a {0,0} (or any other) reading an unfixed vehicle sends.
            latitude = null;
            longitude = null;
        }
        altitudeMeters = position.alt() / 1000.0;
        headingDegrees = position.hdg() == UNKNOWN_HEADING_CENTIDEGREES ? null : position.hdg() / 100.0;
        vxMps = position.vx() / 100.0;
        vyMps = position.vy() / 100.0;
        vzMps = position.vz() / 100.0;
        aglMeters = position.relativeAlt() / 1000.0;
        deviceBootMillis = position.timeBootMs();
    }

    void applyBatteryPercent(int batteryRemainingPercent) {
        if (batteryRemainingPercent != UNKNOWN_BATTERY_PERCENT) {
            batteryPercent = (double) batteryRemainingPercent;
        }
    }

    void applyBatteryVoltage(int voltageBatteryMillivolts) {
        batteryVoltage =
                voltageBatteryMillivolts == UNKNOWN_VOLTAGE_BATTERY_MILLIVOLTS ? null : voltageBatteryMillivolts / 1000.0;
    }
}

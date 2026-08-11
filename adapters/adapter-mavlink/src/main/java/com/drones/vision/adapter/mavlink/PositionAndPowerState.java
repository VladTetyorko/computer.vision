package com.drones.vision.adapter.mavlink;

import io.dronefleet.mavlink.common.GlobalPositionInt;

/**
 * {@link MavlinkTelemetryDecoder}'s accumulated position/velocity/battery fields —
 * {@code GLOBAL_POSITION_INT} and {@code SYS_STATUS}/{@code BATTERY_STATUS} — the subset that maps
 * onto {@link com.drones.vision.domain.model.Telemetry}'s own named fields plus the velocity/
 * battery-voltage {@code extra} keys (docs/plans/active/LAYERING-REFACTOR-PLAN.md E2 split out of {@code
 * MavlinkTelemetryDecoder}'s original 35-field flat state). See {@code MavlinkTelemetryDecoder}'s
 * own javadoc for the full unit-conversion table.
 *
 * <p>Package-private mutable struct, not a record: every field starts {@code null} ("not yet
 * known") and is merged in place as messages arrive, exactly like the flat-field version this
 * replaces. One instance per {@link MavlinkTelemetryDecoder}, touched only from that decoder's own
 * caller thread (the owning {@code MavlinkSocketHub}'s single read thread) — no locking of its own.
 * Fields are package-private with no accessors: {@link MavlinkTelemetryDecoder} is this type's only
 * collaborator.
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

    void applyPosition(GlobalPositionInt position) {
        latitude = position.lat() / 1e7;
        longitude = position.lon() / 1e7;
        altitudeMeters = position.alt() / 1000.0;
        headingDegrees = position.hdg() == UNKNOWN_HEADING_CENTIDEGREES ? null : position.hdg() / 100.0;
        vxMps = position.vx() / 100.0;
        vyMps = position.vy() / 100.0;
        vzMps = position.vz() / 100.0;
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

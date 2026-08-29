package com.drones.vision.adapter.mavlink;

import com.drones.vision.kernel.FlightState;

import io.dronefleet.mavlink.common.GpsFixType;
import io.dronefleet.mavlink.common.GpsRawInt;
import io.dronefleet.mavlink.minimal.Heartbeat;
import io.dronefleet.mavlink.minimal.MavState;
import io.dronefleet.mavlink.util.EnumValue;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link MavlinkTelemetryDecoder}'s accumulated flight-controller-state fields — {@code HEARTBEAT},
 * {@code GPS_RAW_INT}, {@code RC_CHANNELS}/{@code RC_CHANNELS_RAW}, {@code STATUSTEXT} — the subset
 * that materializes into {@link FlightState} (docs/plans/done/FC-INTEGRATIONS-PLAN.md F-a;
 * docs/plans/active/LAYERING-REFACTOR-PLAN.md E2 split out of {@code MavlinkTelemetryDecoder}'s original 35-field
 * flat state). See {@code MavlinkTelemetryDecoder}'s own javadoc for the full unit-conversion table.
 *
 * <p>Package-private mutable struct, not a record — see {@link PositionAndPowerState}'s javadoc for
 * the same "one per decoder, no locking, no accessors" reasoning, which applies identically here.
 */
final class FlightStatusState {

    private static final int UNKNOWN_SATELLITES = 255;
    private static final int UNKNOWN_EPH_CENTIUNITS = 65535;
    private static final int UNKNOWN_RSSI = 255;

    /**
     * The wire value of {@link GpsFixType#GPS_FIX_TYPE_2D_FIX}, read off the enum itself (the
     * project's established {@code EnumValue.of(...).value()} idiom, e.g. {@code CommandService}/
     * {@code CapabilityService} in {@code mavlink-core}) rather than hardcoded as the literal
     * {@code 2} — docs/plans/active/OPERATOR-UX-4-PLAN.md N1.
     */
    private static final int GPS_FIX_TYPE_2D_FIX_VALUE = EnumValue.of(GpsFixType.GPS_FIX_TYPE_2D_FIX).value();

    private static final int MAV_MODE_FLAG_SAFETY_ARMED = 128;
    private static final int MAV_MODE_FLAG_CUSTOM_MODE_ENABLED = 1;

    private static final int MAX_ARMING_BLOCKERS = 10;
    private static final Pattern ARMING_BLOCKER_PATTERN = Pattern.compile("^(?:PreArm|Arm): (.*)$");

    String firmware;
    String mode;
    Boolean armed;
    Boolean failsafe;
    Integer gpsFixType;
    Integer satellites;
    Double hdop;
    Integer rssiPercent;
    final Set<String> armingBlockers = new LinkedHashSet<>();

    void applyHeartbeat(Heartbeat heartbeat) {
        int autopilot = heartbeat.autopilot().value();
        firmware = firmwareLabel(autopilot);

        int baseMode = heartbeat.baseMode().value();
        armed = (baseMode & MAV_MODE_FLAG_SAFETY_ARMED) != 0;
        if (Boolean.TRUE.equals(armed)) {
            armingBlockers.clear(); // arming resolves/discards whatever was blocking it, see class javadoc
        }
        if ((baseMode & MAV_MODE_FLAG_CUSTOM_MODE_ENABLED) != 0) {
            int mavType = heartbeat.type().value();
            mode = FlightModes.name(autopilot, mavType, heartbeat.customMode());
        } // else: custom_mode isn't valid on the wire -- leave the last known mode unchanged.

        failsafe = heartbeat.systemStatus().entry() == MavState.MAV_STATE_CRITICAL;
    }

    /** Package-private (not {@code private}): reused by {@link MavlinkTelemetryDecoder#firmwareLabel} and {@link VehicleClaimPolicy}. */
    static String firmwareLabel(int autopilot) {
        if (autopilot == FlightModes.AUTOPILOT_ARDUPILOTMEGA) {
            return "ardupilot";
        }
        if (autopilot == FlightModes.AUTOPILOT_GENERIC) {
            return "generic";
        }
        if (autopilot == FlightModes.AUTOPILOT_PX4) {
            return "px4";
        }
        return null;
    }

    void applyGps(GpsRawInt gpsRawInt) {
        gpsFixType = gpsRawInt.fixType().value();
        int satellitesVisible = gpsRawInt.satellitesVisible();
        satellites = satellitesVisible == UNKNOWN_SATELLITES ? null : satellitesVisible;
        int ephCentiunits = gpsRawInt.eph();
        hdop = ephCentiunits == UNKNOWN_EPH_CENTIUNITS ? null : ephCentiunits / 100.0;
    }

    /**
     * {@code true} once {@code GPS_RAW_INT} has reported at least a 2D fix. {@code false} both for
     * an explicit sub-2D fix (no fix / no GPS at all) <b>and</b> for "no {@code GPS_RAW_INT} has
     * ever arrived" ({@link #gpsFixType} still {@code null}) — an unknown fix state must never be
     * treated as "assume it's fine" (docs/plans/active/OPERATOR-UX-4-PLAN.md N1: a real ESP32 rover
     * with no fix sends {@code GLOBAL_POSITION_INT} {@code lat=lon=0} regardless, which this decoder
     * must never record as a real position — see {@link PositionAndPowerState#applyPosition}, the
     * one caller of this method).
     */
    boolean hasGpsFix() {
        return gpsFixType != null && gpsFixType >= GPS_FIX_TYPE_2D_FIX_VALUE;
    }

    void applyRssi(int rssiRaw) {
        rssiPercent = rssiRaw == UNKNOWN_RSSI ? null : (int) Math.round(rssiRaw / 254.0 * 100.0);
    }

    void applyStatustext(String text) {
        if (text == null) {
            return;
        }
        Matcher matcher = ARMING_BLOCKER_PATTERN.matcher(text);
        if (!matcher.matches()) {
            return;
        }
        if (armingBlockers.add(matcher.group(1))) {
            while (armingBlockers.size() > MAX_ARMING_BLOCKERS) {
                Iterator<String> oldest = armingBlockers.iterator();
                oldest.next();
                oldest.remove();
            }
        }
    }

    /** {@code null} until at least one {@link FlightState} field has actually become known — see {@code MavlinkTelemetryDecoder}'s class javadoc. */
    FlightState toFlightStateOrNull() {
        if (firmware == null && mode == null && armed == null && failsafe == null && gpsFixType == null
                && satellites == null && hdop == null && rssiPercent == null && armingBlockers.isEmpty()) {
            return null;
        }
        return new FlightState(firmware, mode, armed, failsafe, gpsFixType, satellites, hdop, rssiPercent,
                List.copyOf(armingBlockers));
    }
}

package com.drones.vision.adapter.mavlink;

import com.drones.vision.domain.model.FlightState;

import io.dronefleet.mavlink.common.GpsRawInt;
import io.dronefleet.mavlink.minimal.Heartbeat;
import io.dronefleet.mavlink.minimal.MavState;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link MavlinkTelemetryDecoder}'s accumulated flight-controller-state fields — {@code HEARTBEAT},
 * {@code GPS_RAW_INT}, {@code RC_CHANNELS}/{@code RC_CHANNELS_RAW}, {@code STATUSTEXT} — the subset
 * that materializes into {@link FlightState} (docs/FC-INTEGRATIONS-PLAN.md F-a;
 * docs/LAYERING-REFACTOR-PLAN.md E2 split out of {@code MavlinkTelemetryDecoder}'s original 35-field
 * flat state). See {@code MavlinkTelemetryDecoder}'s own javadoc for the full unit-conversion table.
 *
 * <p>Package-private mutable struct, not a record — see {@link PositionAndPowerState}'s javadoc for
 * the same "one per decoder, no locking, no accessors" reasoning, which applies identically here.
 */
final class FlightStatusState {

    private static final int UNKNOWN_SATELLITES = 255;
    private static final int UNKNOWN_EPH_CENTIUNITS = 65535;
    private static final int UNKNOWN_RSSI = 255;

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

    /** Package-private (not {@code private}): reused by {@link MavlinkTelemetryDecoder#firmwareLabel} and {@link VehicleClaimRegistry}. */
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

package com.drones.vision.adapter.mavlink;

import io.dronefleet.mavlink.common.GlobalPositionInt;
import io.dronefleet.mavlink.common.GpsFixType;
import io.dronefleet.mavlink.common.GpsRawInt;
import io.dronefleet.mavlink.common.MavSysStatusSensor;
import io.dronefleet.mavlink.common.MavSysStatusSensorExtended;
import io.dronefleet.mavlink.common.SysStatus;
import io.dronefleet.mavlink.minimal.Heartbeat;
import io.dronefleet.mavlink.minimal.MavAutopilot;
import io.dronefleet.mavlink.minimal.MavModeFlag;
import io.dronefleet.mavlink.minimal.MavState;
import io.dronefleet.mavlink.minimal.MavType;
import io.dronefleet.mavlink.util.EnumValue;

import java.math.BigInteger;

/**
 * Stateless MAVLink message builders for {@link MavlinkFeedTransmitter}'s synthetic flight
 * (docs/LAYERING-REFACTOR-PLAN.md E2 split out of {@code MavlinkFeedTransmitter.FeedRuntime}) —
 * pure functions from the current simulated flight values to a wire-ready message, no field state,
 * no IO. {@link MavlinkFeedTransmitter.FeedRuntime} owns pacing/threading/sending; this class only
 * knows how to build the four message types it sends.
 */
final class SimulatedVehicleMessages {

    /** ArduPilot copter custom_mode for "Loiter" — the nominal (non-failsafe) heartbeat mode. */
    static final long CUSTOM_MODE_LOITER = 5L;
    /** ArduPilot copter custom_mode for "RTL" — the mode reported once failsafe triggers. */
    static final long CUSTOM_MODE_RTL = 6L;

    private static final int GPS_SATELLITES_VISIBLE = 12;
    private static final int GPS_EPH_CENTIUNITS = 90; // eph x100 -> hdop 0.9

    private SimulatedVehicleMessages() {
    }

    /**
     * @param failsafeTriggered {@code true} once the drained battery has fallen below {@code
     *                          failsafeBatteryPercent} — switches {@code custom_mode} to RTL
     *                          and {@code system_status} to CRITICAL (docs/FC-INTEGRATIONS-PLAN.md F-a)
     */
    static Heartbeat heartbeat(boolean failsafeTriggered) {
        return Heartbeat.builder()
                .type(MavType.MAV_TYPE_QUADROTOR)
                .autopilot(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA)
                .baseMode(MavModeFlag.MAV_MODE_FLAG_SAFETY_ARMED, MavModeFlag.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED)
                .customMode(failsafeTriggered ? CUSTOM_MODE_RTL : CUSTOM_MODE_LOITER)
                .systemStatus(failsafeTriggered ? MavState.MAV_STATE_CRITICAL : MavState.MAV_STATE_ACTIVE)
                .mavlinkVersion(3)
                .build();
    }

    /** Fixed 3D fix, {@value #GPS_SATELLITES_VISIBLE} satellites, {@value #GPS_EPH_CENTIUNITS} eph (hdop 0.9). */
    static GpsRawInt gpsRawInt(MavlinkRoute.Position position) {
        int altMillimeters =
                position.altitudeMeters() == null ? 0 : (int) Math.round(position.altitudeMeters() * 1000.0);
        return GpsRawInt.builder()
                .timeUsec(BigInteger.ZERO)
                .fixType(GpsFixType.GPS_FIX_TYPE_3D_FIX)
                .lat((int) Math.round(position.latitude() * 1e7))
                .lon((int) Math.round(position.longitude() * 1e7))
                .alt(altMillimeters)
                .eph(GPS_EPH_CENTIUNITS)
                .epv(0)
                .vel(0)
                .cog(0)
                .satellitesVisible(GPS_SATELLITES_VISIBLE)
                .build();
    }

    static SysStatus sysStatus(int batteryPercent) {
        return SysStatus.builder()
                .onboardControlSensorsPresent(EnumValue.<MavSysStatusSensor>create(0))
                .onboardControlSensorsEnabled(EnumValue.<MavSysStatusSensor>create(0))
                .onboardControlSensorsHealth(EnumValue.<MavSysStatusSensor>create(0))
                .load(0)
                .voltageBattery(0)
                .currentBattery(-1)
                .batteryRemaining(batteryPercent)
                .dropRateComm(0)
                .errorsComm(0)
                .errorsCount1(0)
                .errorsCount2(0)
                .errorsCount3(0)
                .errorsCount4(0)
                .onboardControlSensorsPresentExtended(EnumValue.<MavSysStatusSensorExtended>create(0))
                .onboardControlSensorsEnabledExtended(EnumValue.<MavSysStatusSensorExtended>create(0))
                .onboardControlSensorsHealthExtended(EnumValue.<MavSysStatusSensorExtended>create(0))
                .build();
    }

    static GlobalPositionInt globalPositionInt(MavlinkRoute.Position position, long elapsedMillis, double speedMps) {
        double headingRadians = Math.toRadians(position.headingDegrees());
        int vxCmS = (int) Math.round(speedMps * Math.cos(headingRadians) * 100.0);
        int vyCmS = (int) Math.round(speedMps * Math.sin(headingRadians) * 100.0);
        int altMillimeters = position.altitudeMeters() == null
                ? 0 : (int) Math.round(position.altitudeMeters() * 1000.0);
        int headingCentidegrees =
                ((int) Math.round(position.headingDegrees() * 100.0) % 36000 + 36000) % 36000;

        return GlobalPositionInt.builder()
                .timeBootMs(elapsedMillis)
                .lat((int) Math.round(position.latitude() * 1e7))
                .lon((int) Math.round(position.longitude() * 1e7))
                .alt(altMillimeters)
                .relativeAlt(altMillimeters)
                .vx(vxCmS)
                .vy(vyCmS)
                .vz(0)
                .hdg(headingCentidegrees)
                .build();
    }
}

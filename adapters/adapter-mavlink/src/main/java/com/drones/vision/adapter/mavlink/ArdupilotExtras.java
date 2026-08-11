package com.drones.vision.adapter.mavlink;

import io.dronefleet.mavlink.ardupilotmega.EkfStatusReport;
import io.dronefleet.mavlink.ardupilotmega.Rangefinder;
import io.dronefleet.mavlink.ardupilotmega.Wind;
import io.dronefleet.mavlink.common.MissionCurrent;
import io.dronefleet.mavlink.common.VfrHud;
import io.dronefleet.mavlink.common.Vibration;

/**
 * {@link MavlinkTelemetryDecoder}'s accumulated {@code extra}-only fields — {@code VFR_HUD}
 * (common), {@code WIND}/{@code EKF_STATUS_REPORT}/{@code RANGEFINDER} (ardupilotmega), {@code
 * VIBRATION}/{@code MISSION_CURRENT} (common) — none of which have a home in {@link
 * com.drones.vision.domain.model.Telemetry}'s named fields or {@link
 * com.drones.vision.domain.model.FlightState} (docs/plans/done/FC-INTEGRATIONS-PLAN.md F-e;
 * docs/plans/active/LAYERING-REFACTOR-PLAN.md E2 split out of {@code MavlinkTelemetryDecoder}'s original
 * 35-field flat state). See {@code MavlinkTelemetryDecoder}'s own javadoc for the full
 * unit-conversion table (none of these fields need one — last-message-wins, no sentinel).
 *
 * <p>Package-private mutable struct, not a record — see {@link PositionAndPowerState}'s javadoc
 * for the same "one per decoder, no locking, no accessors" reasoning, which applies identically
 * here.
 */
final class ArdupilotExtras {

    Double groundspeedMps;
    Double windSpeedMps;
    Double windDirectionDegrees;
    Double vibeXMs2;
    Double vibeYMs2;
    Double vibeZMs2;
    Double ekfVelocityVariance;
    Double ekfPosHorizVariance;
    Double ekfPosVertVariance;
    Double ekfCompassVariance;
    Double missionSeq;
    Double rangefinderDistanceM;

    void applyVfrHud(VfrHud vfrHud) {
        groundspeedMps = (double) vfrHud.groundspeed();
    }

    void applyWind(Wind wind) {
        windDirectionDegrees = (double) wind.direction();
        windSpeedMps = (double) wind.speed();
    }

    void applyVibration(Vibration vibration) {
        vibeXMs2 = (double) vibration.vibrationX();
        vibeYMs2 = (double) vibration.vibrationY();
        vibeZMs2 = (double) vibration.vibrationZ();
    }

    void applyEkfStatusReport(EkfStatusReport ekfStatusReport) {
        ekfVelocityVariance = (double) ekfStatusReport.velocityVariance();
        ekfPosHorizVariance = (double) ekfStatusReport.posHorizVariance();
        ekfPosVertVariance = (double) ekfStatusReport.posVertVariance();
        ekfCompassVariance = (double) ekfStatusReport.compassVariance();
    }

    void applyMissionCurrent(MissionCurrent missionCurrent) {
        missionSeq = (double) missionCurrent.seq();
    }

    void applyRangefinder(Rangefinder rangefinder) {
        rangefinderDistanceM = (double) rangefinder.distance();
    }
}

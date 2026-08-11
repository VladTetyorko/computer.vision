package com.drones.vision.adapter.mavlink;

import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.FlightState;
import com.drones.vision.kernel.Telemetry;

import io.dronefleet.mavlink.MavlinkConnection;
import io.dronefleet.mavlink.MavlinkMessage;
import io.dronefleet.mavlink.ardupilotmega.EkfStatusFlags;
import io.dronefleet.mavlink.ardupilotmega.EkfStatusReport;
import io.dronefleet.mavlink.ardupilotmega.Rangefinder;
import io.dronefleet.mavlink.ardupilotmega.Wind;
import io.dronefleet.mavlink.common.Attitude;
import io.dronefleet.mavlink.common.BatteryStatus;
import io.dronefleet.mavlink.common.GlobalPositionInt;
import io.dronefleet.mavlink.common.GpsFixType;
import io.dronefleet.mavlink.common.GpsRawInt;
import io.dronefleet.mavlink.common.MavBatteryChargeState;
import io.dronefleet.mavlink.common.MavBatteryFault;
import io.dronefleet.mavlink.common.MavBatteryFunction;
import io.dronefleet.mavlink.common.MavBatteryMode;
import io.dronefleet.mavlink.common.MavBatteryType;
import io.dronefleet.mavlink.common.MavSeverity;
import io.dronefleet.mavlink.common.MavSysStatusSensor;
import io.dronefleet.mavlink.common.MavSysStatusSensorExtended;
import io.dronefleet.mavlink.common.MissionCurrent;
import io.dronefleet.mavlink.common.MissionState;
import io.dronefleet.mavlink.common.RcChannels;
import io.dronefleet.mavlink.common.RcChannelsRaw;
import io.dronefleet.mavlink.common.Statustext;
import io.dronefleet.mavlink.common.SysStatus;
import io.dronefleet.mavlink.common.VfrHud;
import io.dronefleet.mavlink.common.Vibration;
import io.dronefleet.mavlink.minimal.Heartbeat;
import io.dronefleet.mavlink.minimal.MavAutopilot;
import io.dronefleet.mavlink.minimal.MavModeFlag;
import io.dronefleet.mavlink.minimal.MavState;
import io.dronefleet.mavlink.minimal.MavType;
import io.dronefleet.mavlink.util.EnumValue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-bytes decode tests: every fixture is a real MAVLink 2 frame produced by {@link
 * MavlinkConnection}'s own encoder (correct framing/CRC included, exactly what a real radio or
 * SITL instance would put on the wire) from known field values, decoded back through {@link
 * MavlinkConnection#next()}, and run through {@link MavlinkTelemetryDecoder} — so these tests
 * exercise the real wire format, not just this module's own mapping logic in isolation.
 */
class MavlinkTelemetryDecoderTest {

    private static final int COMPONENT_ID = 1;
    private static final DeviceId DEVICE_ID = DeviceId.random();

    @Test
    void mapsGlobalPositionIntWithUnitConversions() throws IOException {
        GlobalPositionInt payload = GlobalPositionInt.builder()
                .timeBootMs(1000L)
                .lat(504500000)   // 1e7 -> 50.45 degrees
                .lon(305200000)   // 1e7 -> 30.52 degrees
                .alt(123456)      // mm -> 123.456 m
                .relativeAlt(100000)
                .vx(500)          // cm/s -> 5.0 m/s
                .vy(-250)         // cm/s -> -2.5 m/s
                .vz(10)           // cm/s -> 0.1 m/s
                .hdg(9000)        // centidegrees -> 90.0 degrees
                .build();

        Telemetry sample = decode(1, payload);

        assertNotNull(sample);
        assertEquals(50.45, sample.latitude(), 1e-9);
        assertEquals(30.52, sample.longitude(), 1e-9);
        assertEquals(123.456, sample.altitudeMeters(), 1e-9);
        assertEquals(90.0, sample.headingDegrees(), 1e-9);
        assertEquals(5.0, sample.extra().get("vxMps"), 1e-9);
        assertEquals(-2.5, sample.extra().get("vyMps"), 1e-9);
        assertEquals(0.1, sample.extra().get("vzMps"), 1e-9);
        assertEquals(DEVICE_ID, sample.deviceId());
    }

    @Test
    void mapsUnknownHeadingSentinelToNull() throws IOException {
        GlobalPositionInt payload = GlobalPositionInt.builder()
                .timeBootMs(0L).lat(0).lon(0).alt(0).relativeAlt(0).vx(0).vy(0).vz(0)
                .hdg(65535) // UINT16_MAX: "unknown" per the MAVLink spec
                .build();

        Telemetry sample = decode(1, payload);

        assertNotNull(sample);
        assertNull(sample.headingDegrees());
    }

    @Test
    void mapsBatteryPercentFromSysStatusThenBatteryStatusOverridesItAsTheMoreRecentReading() throws IOException {
        MavlinkTelemetryDecoder decoder = new MavlinkTelemetryDecoder(DEVICE_ID);

        Telemetry afterSysStatus = decoder.accept(encodeThenDecode(1, sysStatus(77)));
        assertNotNull(afterSysStatus);
        assertEquals(77.0, afterSysStatus.batteryPercent());

        Telemetry afterBatteryStatus = decoder.accept(encodeThenDecode(1, batteryStatus(50)));
        assertNotNull(afterBatteryStatus);
        assertEquals(50.0, afterBatteryStatus.batteryPercent());
    }

    @Test
    void unknownBatteryRemainingSentinelLeavesThePreviousReadingUnchanged() throws IOException {
        MavlinkTelemetryDecoder decoder = new MavlinkTelemetryDecoder(DEVICE_ID);

        decoder.accept(encodeThenDecode(1, sysStatus(60)));
        Telemetry afterUnknown = decoder.accept(encodeThenDecode(1, sysStatus(-1)));

        assertNotNull(afterUnknown);
        assertEquals(60.0, afterUnknown.batteryPercent(), "an unknown (-1) reading must not overwrite a known one");
    }

    @Test
    void mapsGroundspeedFromVfrHudWithNoConversion() throws IOException {
        VfrHud payload = VfrHud.builder()
                .airspeed(12.3f).groundspeed(7.5f).heading(90).throttle(50).alt(100.0f).climb(0.0f)
                .build();

        Telemetry sample = decode(1, payload);

        assertNotNull(sample);
        assertEquals(7.5, sample.extra().get("groundspeedMps"), 1e-6);
    }

    @Test
    void heartbeatEmitsASampleWithoutChangingAnyOtherField() throws IOException {
        MavlinkTelemetryDecoder decoder = new MavlinkTelemetryDecoder(DEVICE_ID);
        decoder.accept(encodeThenDecode(1, GlobalPositionInt.builder()
                .timeBootMs(0L).lat(100000000).lon(200000000).alt(0).relativeAlt(0).vx(0).vy(0).vz(0).hdg(0)
                .build()));

        Telemetry afterHeartbeat = decoder.accept(encodeThenDecode(1, heartbeat()));

        assertNotNull(afterHeartbeat, "a heartbeat must still emit a sample (liveness)");
        assertEquals(10.0, afterHeartbeat.latitude(), 1e-9, "heartbeat must not clear previously known fields");
    }

    @Test
    void ignoresUnrecognizedMessageTypes() throws IOException {
        MavlinkTelemetryDecoder decoder = new MavlinkTelemetryDecoder(DEVICE_ID);

        Attitude unrecognized = Attitude.builder()
                .timeBootMs(0L).roll(0f).pitch(0f).yaw(0f).rollspeed(0f).pitchspeed(0f).yawspeed(0f)
                .build();

        assertNull(decoder.accept(encodeThenDecode(1, unrecognized)));
    }

    @Test
    void locksOntoTheFirstSystemIdAndIgnoresAllOthers() throws IOException {
        MavlinkTelemetryDecoder decoder = new MavlinkTelemetryDecoder(DEVICE_ID);

        Telemetry fromFirstSystem = decoder.accept(encodeThenDecode(5, GlobalPositionInt.builder()
                .timeBootMs(0L).lat(100000000).lon(100000000).alt(0).relativeAlt(0).vx(0).vy(0).vz(0).hdg(0)
                .build()));
        assertNotNull(fromFirstSystem);
        assertEquals(10.0, fromFirstSystem.latitude(), 1e-9);

        // A second system sharing the port: ignored entirely, state from the first is untouched.
        Telemetry fromSecondSystem = decoder.accept(encodeThenDecode(9, GlobalPositionInt.builder()
                .timeBootMs(0L).lat(999999990).lon(999999990).alt(0).relativeAlt(0).vx(0).vy(0).vz(0).hdg(0)
                .build()));
        assertNull(fromSecondSystem);

        Telemetry stillFromFirstSystem = decoder.accept(encodeThenDecode(5, heartbeat()));
        assertNotNull(stillFromFirstSystem);
        assertEquals(10.0, stillFromFirstSystem.latitude(), 1e-9,
                "the second system's position must never have been applied");
    }

    @Test
    void flightStateIsNullUntilAnyFlightControllerMessageArrives() throws IOException {
        MavlinkTelemetryDecoder decoder = new MavlinkTelemetryDecoder(DEVICE_ID);

        Telemetry afterPosition = decoder.accept(encodeThenDecode(1, GlobalPositionInt.builder()
                .timeBootMs(0L).lat(0).lon(0).alt(0).relativeAlt(0).vx(0).vy(0).vz(0).hdg(0).build()));
        assertNotNull(afterPosition);
        assertNull(afterPosition.flightState(), "position/battery-only samples carry no flight-controller state");

        Telemetry afterSysStatus = decoder.accept(encodeThenDecode(1, sysStatus(50)));
        assertNotNull(afterSysStatus);
        assertNull(afterSysStatus.flightState(),
                "SYS_STATUS contributes only extra.batteryVoltage, never materializes FlightState by itself");

        Telemetry afterHeartbeat = decoder.accept(encodeThenDecode(1,
                heartbeat(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA, MavType.MAV_TYPE_QUADROTOR, false, false, 0L,
                        MavState.MAV_STATE_ACTIVE)));
        assertNotNull(afterHeartbeat.flightState(), "a HEARTBEAT must materialize a non-null FlightState");
    }

    @Test
    void decodesArdupilotCopterModeFromHeartbeat() throws IOException {
        Telemetry sample = decode(1, heartbeat(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA, MavType.MAV_TYPE_QUADROTOR,
                true, true, 6L, MavState.MAV_STATE_ACTIVE));

        assertEquals("ardupilot", sample.flightState().firmware());
        assertEquals("RTL", sample.flightState().mode());
    }

    @Test
    void decodesArdupilotPlaneModeFromHeartbeat() throws IOException {
        Telemetry sample = decode(1, heartbeat(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA, MavType.MAV_TYPE_FIXED_WING,
                true, true, 11L, MavState.MAV_STATE_ACTIVE));

        assertEquals("ardupilot", sample.flightState().firmware());
        assertEquals("RTL", sample.flightState().mode());
    }

    @Test
    void decodesBetaflightModeFromHeartbeat() throws IOException {
        Telemetry sample = decode(1, heartbeat(MavAutopilot.MAV_AUTOPILOT_GENERIC, MavType.MAV_TYPE_QUADROTOR,
                true, true, 6L, MavState.MAV_STATE_ACTIVE));

        assertEquals("generic", sample.flightState().firmware());
        assertEquals("RTL", sample.flightState().mode());
    }

    @Test
    void modeIsLeftUnchangedWhenCustomModeIsNotEnabled() throws IOException {
        MavlinkTelemetryDecoder mavlinkTelemetryDecoder = new MavlinkTelemetryDecoder(DEVICE_ID);
        Telemetry withMode = mavlinkTelemetryDecoder.accept(encodeThenDecode(1,
                heartbeat(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA, MavType.MAV_TYPE_QUADROTOR, true, true, 6L,
                        MavState.MAV_STATE_ACTIVE)));
        assertEquals("RTL", withMode.flightState().mode());

        Telemetry withoutCustomModeBit = mavlinkTelemetryDecoder.accept(encodeThenDecode(1,
                heartbeat(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA, MavType.MAV_TYPE_QUADROTOR, true, false, 3L,
                        MavState.MAV_STATE_ACTIVE)));
        assertEquals("RTL", withoutCustomModeBit.flightState().mode(),
                "base_mode without CUSTOM_MODE_ENABLED must leave the last known mode unchanged");
    }

    @Test
    void decodesArmedFlagFromBaseModeSafetyArmedBit() throws IOException {
        Telemetry armed = decode(1, heartbeat(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA, MavType.MAV_TYPE_QUADROTOR,
                true, true, 5L, MavState.MAV_STATE_ACTIVE));
        assertEquals(true, armed.flightState().armed());

        Telemetry disarmed = decode(1, heartbeat(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA, MavType.MAV_TYPE_QUADROTOR,
                false, true, 5L, MavState.MAV_STATE_ACTIVE));
        assertEquals(false, disarmed.flightState().armed());
    }

    @Test
    void decodesFailsafeFromCriticalSystemStatus() throws IOException {
        Telemetry nominal = decode(1, heartbeat(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA, MavType.MAV_TYPE_QUADROTOR,
                true, true, 6L, MavState.MAV_STATE_ACTIVE));
        assertEquals(false, nominal.flightState().failsafe());

        Telemetry critical = decode(1, heartbeat(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA, MavType.MAV_TYPE_QUADROTOR,
                true, true, 6L, MavState.MAV_STATE_CRITICAL));
        assertEquals(true, critical.flightState().failsafe());
    }

    @Test
    void mapsGpsFixTypeSatellitesAndHdopWithSentinelHandling() throws IOException {
        Telemetry fix = decode(1, gpsRawInt(GpsFixType.GPS_FIX_TYPE_3D_FIX, 12, 90));

        assertEquals(3, fix.flightState().gpsFixType());
        assertEquals(12, fix.flightState().satellites());
        assertEquals(0.9, fix.flightState().hdop(), 1e-9);
    }

    @Test
    void mapsUnknownSatellitesAndEphSentinelsToNull() throws IOException {
        Telemetry sample = decode(1, gpsRawInt(GpsFixType.GPS_FIX_TYPE_NO_FIX, 255, 65535));

        assertEquals(1, sample.flightState().gpsFixType());
        assertNull(sample.flightState().satellites());
        assertNull(sample.flightState().hdop());
    }

    @Test
    void mapsRssiFromRcChannelsAndRcChannelsRawWithPercentConversion() throws IOException {
        Telemetry fromRcChannels = decode(1, rcChannels(127));
        assertEquals((int) Math.round(127 / 254.0 * 100.0), fromRcChannels.flightState().rssiPercent());

        Telemetry fromRcChannelsRaw = decode(1, rcChannelsRaw(254));
        assertEquals(100, fromRcChannelsRaw.flightState().rssiPercent());
    }

    @Test
    void mapsUnknownRssiSentinelToNull() throws IOException {
        // A bare 255-rssi RC_CHANNELS teaches the decoder nothing (flightState would stay null
        // entirely, honestly) -- prime a HEARTBEAT first so there's a non-null FlightState to
        // assert the null rssiPercent field against.
        MavlinkTelemetryDecoder viaRcChannels = new MavlinkTelemetryDecoder(DEVICE_ID);
        viaRcChannels.accept(encodeThenDecode(1, heartbeat(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA,
                MavType.MAV_TYPE_QUADROTOR, true, true, 5L, MavState.MAV_STATE_ACTIVE)));
        Telemetry fromRcChannels = viaRcChannels.accept(encodeThenDecode(1, rcChannels(255)));
        assertNull(fromRcChannels.flightState().rssiPercent());

        MavlinkTelemetryDecoder viaRcChannelsRaw = new MavlinkTelemetryDecoder(DEVICE_ID);
        viaRcChannelsRaw.accept(encodeThenDecode(1, heartbeat(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA,
                MavType.MAV_TYPE_QUADROTOR, true, true, 5L, MavState.MAV_STATE_ACTIVE)));
        Telemetry fromRcChannelsRaw = viaRcChannelsRaw.accept(encodeThenDecode(1, rcChannelsRaw(255)));
        assertNull(fromRcChannelsRaw.flightState().rssiPercent());
    }

    @Test
    void capturesPreArmAndArmStatustextReasonsAsArmingBlockers() throws IOException {
        MavlinkTelemetryDecoder mavlinkTelemetryDecoder = new MavlinkTelemetryDecoder(DEVICE_ID);

        Telemetry afterFirst = mavlinkTelemetryDecoder.accept(
                encodeThenDecode(1, statustext("PreArm: Compass not calibrated")));
        assertEquals(List.of("Compass not calibrated"), afterFirst.flightState().armingBlockers());

        Telemetry afterSecond = mavlinkTelemetryDecoder.accept(
                encodeThenDecode(1, statustext("Arm: GPS not healthy")));
        assertEquals(List.of("Compass not calibrated", "GPS not healthy"), afterSecond.flightState().armingBlockers(),
                "arming blockers accumulate in insertion order");
    }

    @Test
    void nonMatchingStatustextIsRecognizedButDoesNotChangeArmingBlockers() throws IOException {
        MavlinkTelemetryDecoder mavlinkTelemetryDecoder = new MavlinkTelemetryDecoder(DEVICE_ID);

        Telemetry sample = mavlinkTelemetryDecoder.accept(encodeThenDecode(1, statustext("GPS Glitch")));

        assertNotNull(sample, "a recognized STATUSTEXT must still emit a sample");
        assertNull(sample.flightState(), "text not matching the PreArm/Arm pattern contributes no FlightState field");
    }

    @Test
    void armingBlockersAreClearedTheMomentHeartbeatReportsArmed() throws IOException {
        MavlinkTelemetryDecoder mavlinkTelemetryDecoder = new MavlinkTelemetryDecoder(DEVICE_ID);
        mavlinkTelemetryDecoder.accept(encodeThenDecode(1, statustext("PreArm: Compass not calibrated")));

        Telemetry afterArming = mavlinkTelemetryDecoder.accept(encodeThenDecode(1,
                heartbeat(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA, MavType.MAV_TYPE_QUADROTOR, true, true, 5L,
                        MavState.MAV_STATE_ACTIVE)));

        assertTrue(afterArming.flightState().armingBlockers().isEmpty(),
                "arming must clear every previously accumulated blocker");
    }

    @Test
    void armingBlockersAreCappedAtTenOldestEvictedFirst() throws IOException {
        MavlinkTelemetryDecoder mavlinkTelemetryDecoder = new MavlinkTelemetryDecoder(DEVICE_ID);
        Telemetry sample = null;
        for (int i = 0; i < 12; i++) {
            sample = mavlinkTelemetryDecoder.accept(encodeThenDecode(1, statustext("PreArm: reason " + i)));
        }

        List<String> blockers = sample.flightState().armingBlockers();
        assertEquals(10, blockers.size());
        assertEquals("reason 2", blockers.get(0), "the two oldest reasons must have been evicted");
        assertEquals("reason 11", blockers.get(blockers.size() - 1));
    }

    @Test
    void mapsBatteryVoltageFromSysStatusIntoExtraWithSentinelHandling() throws IOException {
        Telemetry known = decode(1, sysStatusWithVoltage(22_800));
        assertEquals(22.8, known.extra().get("batteryVoltage"), 1e-9);

        Telemetry unknown = decode(1, sysStatusWithVoltage(65535));
        assertNull(unknown.extra().get("batteryVoltage"));
    }

    @Test
    void preExistingPositionBatteryAndHeadingMappingsAreUnchangedAlongsideFlightState() throws IOException {
        MavlinkTelemetryDecoder mavlinkTelemetryDecoder = new MavlinkTelemetryDecoder(DEVICE_ID);
        mavlinkTelemetryDecoder.accept(encodeThenDecode(1, GlobalPositionInt.builder()
                .timeBootMs(0L).lat(504500000).lon(305200000).alt(123456).relativeAlt(0)
                .vx(0).vy(0).vz(0).hdg(9000).build()));
        mavlinkTelemetryDecoder.accept(encodeThenDecode(1, sysStatus(77)));

        Telemetry afterHeartbeat = mavlinkTelemetryDecoder.accept(encodeThenDecode(1,
                heartbeat(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA, MavType.MAV_TYPE_QUADROTOR, true, true, 6L,
                        MavState.MAV_STATE_ACTIVE)));

        assertEquals(50.45, afterHeartbeat.latitude(), 1e-9);
        assertEquals(30.52, afterHeartbeat.longitude(), 1e-9);
        assertEquals(123.456, afterHeartbeat.altitudeMeters(), 1e-9);
        assertEquals(90.0, afterHeartbeat.headingDegrees(), 1e-9);
        assertEquals(77.0, afterHeartbeat.batteryPercent());
        assertNotNull(afterHeartbeat.flightState());
        assertEquals("RTL", afterHeartbeat.flightState().mode());
    }

    // --- docs/plans/done/FC-INTEGRATIONS-PLAN.md F-e: WIND / VIBRATION / EKF_STATUS_REPORT / MISSION_CURRENT / RANGEFINDER ---

    @Test
    void mapsWindIntoExtraViaTheArdupilotmegaDialect() throws IOException {
        Wind payload = Wind.builder().direction(275.5f).speed(6.2f).speedZ(0.4f).build();

        Telemetry sample = decodeAsArdupilotmega(1, payload);

        assertNotNull(sample);
        assertEquals(6.2, sample.extra().get("windSpeedMps"), 1e-6);
        assertEquals(275.5, sample.extra().get("windDirectionDegrees"), 1e-6);
    }

    @Test
    void mapsVibrationIntoExtraSkippingClippingCounts() throws IOException {
        Vibration payload = Vibration.builder()
                .timeUsec(BigInteger.ZERO)
                .vibrationX(12.5f).vibrationY(8.25f).vibrationZ(45.0f)
                .clipping0(3).clipping1(1).clipping2(0)
                .build();

        Telemetry sample = decode(1, payload);

        assertNotNull(sample);
        assertEquals(12.5, sample.extra().get("vibeXMs2"), 1e-6);
        assertEquals(8.25, sample.extra().get("vibeYMs2"), 1e-6);
        assertEquals(45.0, sample.extra().get("vibeZMs2"), 1e-6);
        assertTrue(sample.extra().keySet().stream().noneMatch(key -> key.toLowerCase().contains("clip")),
                "clipping counts are deliberately skipped per docs/plans/done/FC-INTEGRATIONS-PLAN.md F-e");
    }

    @Test
    void mapsEkfStatusReportVariancesIntoExtraViaTheArdupilotmegaDialect() throws IOException {
        EkfStatusReport payload = EkfStatusReport.builder()
                .flags(EnumValue.<EkfStatusFlags>create(0))
                .velocityVariance(0.12f).posHorizVariance(0.34f).posVertVariance(0.56f)
                .compassVariance(0.78f).terrainAltVariance(0f).airspeedVariance(0f)
                .build();

        Telemetry sample = decodeAsArdupilotmega(1, payload);

        assertNotNull(sample);
        assertEquals(0.12, sample.extra().get("ekfVelocityVariance"), 1e-6);
        assertEquals(0.34, sample.extra().get("ekfPosHorizVariance"), 1e-6);
        assertEquals(0.56, sample.extra().get("ekfPosVertVariance"), 1e-6);
        assertEquals(0.78, sample.extra().get("ekfCompassVariance"), 1e-6);
    }

    @Test
    void mapsMissionCurrentSeqIntoExtra() throws IOException {
        MissionCurrent payload = MissionCurrent.builder()
                .seq(7).total(0).missionState(EnumValue.<MissionState>create(0)).missionMode(0)
                .build();

        Telemetry sample = decode(1, payload);

        assertNotNull(sample);
        assertEquals(7.0, sample.extra().get("missionSeq"));
    }

    @Test
    void mapsRangefinderDistanceIntoExtraViaTheArdupilotmegaDialect() throws IOException {
        Rangefinder payload = Rangefinder.builder().distance(4.25f).voltage(0f).build();

        Telemetry sample = decodeAsArdupilotmega(1, payload);

        assertNotNull(sample);
        assertEquals(4.25, sample.extra().get("rangefinderDistanceM"), 1e-6);
    }

    @Test
    void newExtraKeysAreAbsentUntilTheirMessageArrivesAndNeverAppearForNonArdupilotTraffic() throws IOException {
        MavlinkTelemetryDecoder decoder = new MavlinkTelemetryDecoder(DEVICE_ID);

        Telemetry beforeAnyNewMessage = decoder.accept(encodeThenDecode(1, GlobalPositionInt.builder()
                .timeBootMs(0L).lat(0).lon(0).alt(0).relativeAlt(0).vx(0).vy(0).vz(0).hdg(0).build()));
        assertNotNull(beforeAnyNewMessage);
        assertNoNewExtraKeys(beforeAnyNewMessage, "position-only samples carry none of the F-e extras yet");

        // A Betaflight (autopilot=GENERIC) heartbeat: real Betaflight firmware never emits
        // WIND/EKF_STATUS_REPORT/RANGEFINDER (ArduPilot-only messages), so these keys stay absent
        // even once ordinary flight-state traffic is flowing.
        Telemetry afterGenericHeartbeat = decoder.accept(encodeThenDecode(1,
                heartbeat(MavAutopilot.MAV_AUTOPILOT_GENERIC, MavType.MAV_TYPE_QUADROTOR, true, true, 3L,
                        MavState.MAV_STATE_ACTIVE)));
        assertNotNull(afterGenericHeartbeat);
        assertNoNewExtraKeys(afterGenericHeartbeat, "a non-ArduPilot heartbeat contributes no F-e extra");

        // MISSION_CURRENT is a *common*-dialect message, so it decodes and contributes its own key
        // regardless of firmware -- but must still never leak the other four F-e keys.
        Telemetry afterMissionCurrent = decoder.accept(encodeThenDecode(1, MissionCurrent.builder()
                .seq(2).total(0).missionState(EnumValue.<MissionState>create(0)).missionMode(0).build()));
        assertNotNull(afterMissionCurrent);
        assertEquals(2.0, afterMissionCurrent.extra().get("missionSeq"));
        assertNull(afterMissionCurrent.extra().get("windSpeedMps"));
        assertNull(afterMissionCurrent.extra().get("windDirectionDegrees"));
        assertNull(afterMissionCurrent.extra().get("vibeXMs2"));
        assertNull(afterMissionCurrent.extra().get("ekfVelocityVariance"));
        assertNull(afterMissionCurrent.extra().get("rangefinderDistanceM"));
    }

    private static void assertNoNewExtraKeys(Telemetry sample, String message) {
        assertNull(sample.extra().get("windSpeedMps"), message);
        assertNull(sample.extra().get("windDirectionDegrees"), message);
        assertNull(sample.extra().get("vibeXMs2"), message);
        assertNull(sample.extra().get("vibeYMs2"), message);
        assertNull(sample.extra().get("vibeZMs2"), message);
        assertNull(sample.extra().get("ekfVelocityVariance"), message);
        assertNull(sample.extra().get("ekfPosHorizVariance"), message);
        assertNull(sample.extra().get("ekfPosVertVariance"), message);
        assertNull(sample.extra().get("ekfCompassVariance"), message);
        assertNull(sample.extra().get("missionSeq"), message);
        assertNull(sample.extra().get("rangefinderDistanceM"), message);
    }

    private static Telemetry decode(int systemId, Object payload) throws IOException {
        return new MavlinkTelemetryDecoder(DEVICE_ID).accept(encodeThenDecode(systemId, payload));
    }

    /**
     * Like {@link #decode(int, Object)}, but for messages that live in the {@code ardupilotmega}
     * dialect ({@code WIND}/{@code EKF_STATUS_REPORT}/{@code RANGEFINDER}): see {@link
     * #encodeThenDecodeAsArdupilotmega} for why a preceding ArduPilot {@code HEARTBEAT} on the same
     * connection is required for these to resolve at all.
     */
    private static Telemetry decodeAsArdupilotmega(int systemId, Object payload) throws IOException {
        return new MavlinkTelemetryDecoder(DEVICE_ID).accept(encodeThenDecodeAsArdupilotmega(systemId, payload));
    }

    /** Round-trips {@code payload} through a real {@link MavlinkConnection} encode+decode pair. */
    private static MavlinkMessage<?> encodeThenDecode(int systemId, Object payload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MavlinkConnection sender = MavlinkConnection.create(InputStream.nullInputStream(), out);
        sender.send2(systemId, COMPONENT_ID, payload);

        MavlinkConnection receiver = MavlinkConnection.create(
                new ByteArrayInputStream(out.toByteArray()), OutputStream.nullOutputStream());
        return receiver.next();
    }

    /**
     * Like {@link #encodeThenDecode}, but first sends an ArduPilot {@code HEARTBEAT} on the same
     * system id over the <b>same</b> sender/receiver connection pair before {@code payload}, then
     * returns the second decoded message. {@link MavlinkConnection} only resolves the {@code
     * ardupilotmega} dialect for a system once it has decoded that system's {@code HEARTBEAT}
     * (cached per-connection-instance in its own {@code systemDialects} map) -- so a message living
     * only in the ardupilotmega dialect (unlike {@code VIBRATION}/{@code MISSION_CURRENT}, which are
     * plain {@code common} dialect and decode fine via {@link #encodeThenDecode} alone) needs this
     * priming to be resolvable at all on a brand-new connection. Production never needs this dance:
     * {@link MavlinkSocketHub}/{@link MavlinkHeartbeatScanner} keep one long-lived {@code
     * MavlinkConnection} open for as long as they run, and a real ArduPilot vehicle's own ~1Hz
     * unsolicited {@code HEARTBEAT} stream teaches it the dialect once, for good -- see {@link
     * MavlinkTelemetryDecoder}'s class javadoc and this module's {@code MODULE.md}.
     */
    private static MavlinkMessage<?> encodeThenDecodeAsArdupilotmega(int systemId, Object payload)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MavlinkConnection sender = MavlinkConnection.create(InputStream.nullInputStream(), out);
        sender.send2(systemId, COMPONENT_ID, heartbeat(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA,
                MavType.MAV_TYPE_QUADROTOR, true, true, 5L, MavState.MAV_STATE_ACTIVE));
        sender.send2(systemId, COMPONENT_ID, payload);

        MavlinkConnection receiver = MavlinkConnection.create(
                new ByteArrayInputStream(out.toByteArray()), OutputStream.nullOutputStream());
        receiver.next(); // the priming HEARTBEAT: consumed only to teach the receiver's dialect map
        return receiver.next();
    }

    private static Heartbeat heartbeat() {
        return Heartbeat.builder()
                .type(MavType.MAV_TYPE_QUADROTOR)
                .autopilot(MavAutopilot.MAV_AUTOPILOT_GENERIC)
                .baseMode(EnumValue.<MavModeFlag>create(0))
                .customMode(0L)
                .systemStatus(MavState.MAV_STATE_ACTIVE)
                .mavlinkVersion(3)
                .build();
    }

    private static SysStatus sysStatus(int batteryRemaining) {
        return SysStatus.builder()
                .onboardControlSensorsPresent(EnumValue.<MavSysStatusSensor>create(0))
                .onboardControlSensorsEnabled(EnumValue.<MavSysStatusSensor>create(0))
                .onboardControlSensorsHealth(EnumValue.<MavSysStatusSensor>create(0))
                .load(0).voltageBattery(0).currentBattery(-1)
                .batteryRemaining(batteryRemaining)
                .dropRateComm(0).errorsComm(0).errorsCount1(0).errorsCount2(0).errorsCount3(0).errorsCount4(0)
                .onboardControlSensorsPresentExtended(EnumValue.<MavSysStatusSensorExtended>create(0))
                .onboardControlSensorsEnabledExtended(EnumValue.<MavSysStatusSensorExtended>create(0))
                .onboardControlSensorsHealthExtended(EnumValue.<MavSysStatusSensorExtended>create(0))
                .build();
    }

    private static BatteryStatus batteryStatus(int batteryRemaining) {
        return BatteryStatus.builder()
                .id(0)
                .batteryFunction(MavBatteryFunction.MAV_BATTERY_FUNCTION_UNKNOWN)
                .type(MavBatteryType.MAV_BATTERY_TYPE_LIPO)
                .temperature(0)
                .voltages(List.of())
                .currentBattery(-1)
                .currentConsumed(-1)
                .energyConsumed(-1)
                .batteryRemaining(batteryRemaining)
                .timeRemaining(0)
                .chargeState(MavBatteryChargeState.MAV_BATTERY_CHARGE_STATE_OK)
                .voltagesExt(List.of())
                .mode(MavBatteryMode.MAV_BATTERY_MODE_UNKNOWN)
                .faultBitmask(EnumValue.<MavBatteryFault>create(0))
                .build();
    }

    private static SysStatus sysStatusWithVoltage(int voltageBatteryMillivolts) {
        return SysStatus.builder()
                .onboardControlSensorsPresent(EnumValue.<MavSysStatusSensor>create(0))
                .onboardControlSensorsEnabled(EnumValue.<MavSysStatusSensor>create(0))
                .onboardControlSensorsHealth(EnumValue.<MavSysStatusSensor>create(0))
                .load(0).voltageBattery(voltageBatteryMillivolts).currentBattery(-1)
                .batteryRemaining(-1)
                .dropRateComm(0).errorsComm(0).errorsCount1(0).errorsCount2(0).errorsCount3(0).errorsCount4(0)
                .onboardControlSensorsPresentExtended(EnumValue.<MavSysStatusSensorExtended>create(0))
                .onboardControlSensorsEnabledExtended(EnumValue.<MavSysStatusSensorExtended>create(0))
                .onboardControlSensorsHealthExtended(EnumValue.<MavSysStatusSensorExtended>create(0))
                .build();
    }

    /**
     * @param armed            sets the {@code base_mode} safety-armed bit (128)
     * @param customModeEnabled sets the {@code base_mode} custom-mode-enabled bit (1), gating whether
     *                          {@code customMode} is resolved into a mode name
     */
    private static Heartbeat heartbeat(MavAutopilot autopilot, MavType type, boolean armed, boolean customModeEnabled,
                                        long customMode, MavState systemStatus) {
        int baseModeBitmask = (armed ? 128 : 0) | (customModeEnabled ? 1 : 0);
        return Heartbeat.builder()
                .type(type)
                .autopilot(autopilot)
                .baseMode(EnumValue.<MavModeFlag>create(baseModeBitmask))
                .customMode(customMode)
                .systemStatus(systemStatus)
                .mavlinkVersion(3)
                .build();
    }

    private static GpsRawInt gpsRawInt(GpsFixType fixType, int satellitesVisible, int ephCentiunits) {
        return GpsRawInt.builder()
                .timeUsec(BigInteger.ZERO)
                .fixType(fixType)
                .lat(0).lon(0).alt(0)
                .eph(ephCentiunits).epv(0).vel(0).cog(0)
                .satellitesVisible(satellitesVisible)
                .build();
    }

    private static RcChannels rcChannels(int rssi) {
        return RcChannels.builder()
                .timeBootMs(0L).chancount(0)
                .chan1Raw(0).chan2Raw(0).chan3Raw(0).chan4Raw(0).chan5Raw(0).chan6Raw(0).chan7Raw(0).chan8Raw(0)
                .chan9Raw(0).chan10Raw(0).chan11Raw(0).chan12Raw(0).chan13Raw(0).chan14Raw(0).chan15Raw(0)
                .chan16Raw(0).chan17Raw(0).chan18Raw(0)
                .rssi(rssi)
                .build();
    }

    private static RcChannelsRaw rcChannelsRaw(int rssi) {
        return RcChannelsRaw.builder()
                .timeBootMs(0L).port(0)
                .chan1Raw(0).chan2Raw(0).chan3Raw(0).chan4Raw(0).chan5Raw(0).chan6Raw(0).chan7Raw(0).chan8Raw(0)
                .rssi(rssi)
                .build();
    }

    private static Statustext statustext(String text) {
        return Statustext.builder()
                .severity(MavSeverity.MAV_SEVERITY_INFO)
                .text(text)
                .id(0)
                .chunkSeq(0)
                .build();
    }
}

package com.drones.vision.adapter.mavlink;

import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.Telemetry;

import io.dronefleet.mavlink.MavlinkConnection;
import io.dronefleet.mavlink.MavlinkMessage;
import io.dronefleet.mavlink.common.Attitude;
import io.dronefleet.mavlink.common.BatteryStatus;
import io.dronefleet.mavlink.common.GlobalPositionInt;
import io.dronefleet.mavlink.common.MavBatteryChargeState;
import io.dronefleet.mavlink.common.MavBatteryFault;
import io.dronefleet.mavlink.common.MavBatteryFunction;
import io.dronefleet.mavlink.common.MavBatteryMode;
import io.dronefleet.mavlink.common.MavBatteryType;
import io.dronefleet.mavlink.common.MavSysStatusSensor;
import io.dronefleet.mavlink.common.MavSysStatusSensorExtended;
import io.dronefleet.mavlink.common.SysStatus;
import io.dronefleet.mavlink.common.VfrHud;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    private static Telemetry decode(int systemId, Object payload) throws IOException {
        return new MavlinkTelemetryDecoder(DEVICE_ID).accept(encodeThenDecode(systemId, payload));
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
}

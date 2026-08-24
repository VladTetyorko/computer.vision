package com.drones.vision.api.support;

import com.drones.vision.api.dto.CreateAssetRequest;
import com.drones.vision.api.dto.RegisterDeviceRequest;
import com.drones.vision.kernel.Capability;
import com.drones.vision.warehouse.application.device.DeviceRegistration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The protocol-aware capability default
 * (docs/plans/active/TELEMETRY-ONLY-ONBOARDING-CONTEXT.md §2 B2, §3).
 *
 * <p>The regression these guard is silent, which is why they exist: before the default depended on
 * the protocol, a {@code mavlink} device registered without capabilities was created successfully
 * and looked healthy, but carried {@code VIDEO} only — so {@code MavlinkTelemetrySource#supports}
 * rejected it, {@code UsageTracker} never subscribed, and manual control refused to command it.
 * Nothing anywhere reported a problem.
 */
class CapabilityParsingTest {

    @Test
    void mavlinkWithoutCapabilitiesDefaultsToTelemetryNotVideo() {
        assertEquals(Set.of(Capability.TELEMETRY), CapabilityParsing.parse(null, "mavlink"));
        assertEquals(Set.of(Capability.TELEMETRY), CapabilityParsing.parse(List.of(), "mavlink"));
        assertEquals(Set.of(Capability.TELEMETRY), CapabilityParsing.parse(null, "MAVLink"));
        assertEquals(Set.of(Capability.TELEMETRY), CapabilityParsing.parse(null, " mavlink "));
    }

    @Test
    void everyOtherProtocolKeepsThePhaseOneVideoDefault() {
        for (String protocol : List.of("rtsp", "mjpeg", "srt", "udp", "v4l2", "file", "sim")) {
            assertEquals(Set.of(Capability.VIDEO), CapabilityParsing.parse(null, protocol), protocol);
        }
        assertEquals(Set.of(Capability.VIDEO), CapabilityParsing.parse(null, null));
        assertEquals(Set.of(Capability.VIDEO), CapabilityParsing.parse(null));
    }

    @Test
    void anExplicitListAlwaysWinsOverTheProtocolDefault() {
        assertEquals(Set.of(Capability.VIDEO), CapabilityParsing.parse(List.of("video"), "mavlink"));
        assertEquals(Set.of(Capability.VIDEO, Capability.TELEMETRY),
                CapabilityParsing.parse(List.of("VIDEO", "telemetry"), "rtsp"));
    }

    @Test
    void anUnknownNameStillFailsLoudlyAndListsTheValidOnes() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CapabilityParsing.parse(List.of("laser"), "mavlink"));
        assertEquals(true, e.getMessage().contains("TELEMETRY"), e.getMessage());
    }

    // --- The two registration DTOs actually route through it ------------------------------------

    @Test
    void createAssetDeviceSpecDefaultsAMavlinkDeviceToTelemetry() {
        DeviceRegistration registration = new CreateAssetRequest.DeviceSpec(
                "ESP32 rover", "mavlink", "udp://0.0.0.0:14550", Map.of("sysid", "1"), null).toRegistration();

        assertEquals(Set.of(Capability.TELEMETRY), registration.capabilities());
    }

    @Test
    void registerDeviceRequestDefaultsAMavlinkDeviceToTelemetry() {
        DeviceRegistration registration = new RegisterDeviceRequest(
                "ESP32 rover", "mavlink", "udp://0.0.0.0:14550", Map.of(), null).toRegistration();

        assertEquals(Set.of(Capability.TELEMETRY), registration.capabilities());
    }

    @Test
    void aCameraRegisteredTheSameWayIsUnaffected() {
        DeviceRegistration registration = new RegisterDeviceRequest(
                "Front camera", "rtsp", "rtsp://cam/stream", Map.of(), null).toRegistration();

        assertEquals(Set.of(Capability.VIDEO), registration.capabilities());
    }
}

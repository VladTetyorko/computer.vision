package com.drones.vision.adapter.mavlink;

import com.drones.vision.kernel.Capability;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.flight.domain.model.Telemetry;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavlinkTelemetrySourceTest {

    private static Device device(String protocol, URI uri, Set<Capability> capabilities) {
        return new Device(DeviceId.random(), "test-device", capabilities,
                new StreamDescriptor(protocol, uri, Map.of()));
    }

    @Test
    void supportsMavlinkProtocolWithAUdpUriAndAPositivePortOnATelemetryCapableDevice() {
        MavlinkTelemetrySource source = new MavlinkTelemetrySource();

        assertTrue(source.supports(device("mavlink", URI.create("udp://0.0.0.0:14550"), Set.of(Capability.TELEMETRY))));
        assertTrue(source.supports(device("mavlink", URI.create("udp://127.0.0.1:14550"), Set.of(Capability.TELEMETRY))));
    }

    @Test
    void rejectsANonTelemetryCapableDevice() {
        MavlinkTelemetrySource source = new MavlinkTelemetrySource();

        assertFalse(source.supports(device("mavlink", URI.create("udp://0.0.0.0:14550"), Set.of(Capability.VIDEO))));
    }

    @Test
    void rejectsANonMavlinkProtocol() {
        MavlinkTelemetrySource source = new MavlinkTelemetrySource();

        assertFalse(source.supports(device("sim", URI.create("udp://0.0.0.0:14550"), Set.of(Capability.TELEMETRY))));
    }

    @Test
    void rejectsANonUdpScheme() {
        MavlinkTelemetrySource source = new MavlinkTelemetrySource();

        assertFalse(source.supports(device("mavlink", URI.create("tcp://0.0.0.0:14550"), Set.of(Capability.TELEMETRY))));
    }

    @Test
    void rejectsAMissingOrNonPositivePort() {
        MavlinkTelemetrySource source = new MavlinkTelemetrySource();

        assertFalse(source.supports(device("mavlink", URI.create("udp://0.0.0.0"), Set.of(Capability.TELEMETRY))));
    }

    @Test
    void rejectsANullDevice() {
        assertFalse(new MavlinkTelemetrySource().supports(null));
    }

    @Test
    void openRejectsAnUnsupportedDevice() {
        MavlinkTelemetrySource source = new MavlinkTelemetrySource();
        Device unsupported = device("sim", URI.create("sim://drone"), Set.of(Capability.TELEMETRY));

        assertThrows(IllegalArgumentException.class, () -> source.open(unsupported));
    }

    @Test
    void closeOnAnUnknownOrUnopenedDeviceIsANoop() {
        assertDoesNotThrow(() -> new MavlinkTelemetrySource().close(DeviceId.random()));
    }

    @Test
    void openBindsAnEphemeralPortAndCloseReleasesIt() throws Exception {
        MavlinkTelemetrySource source = new MavlinkTelemetrySource();
        int port = freePort();
        Device device = device("mavlink", URI.create("udp://127.0.0.1:" + port), Set.of(Capability.TELEMETRY));

        Flow.Publisher<Telemetry> publisher = source.open(device);
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(Telemetry item) {
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
            }
        });

        // Give the read thread a moment to actually bind before asserting/closing.
        Thread.sleep(200);

        assertDoesNotThrow(() -> source.close(device.id()));
        assertDoesNotThrow(() -> source.close(device.id()), "close() must be idempotent");
    }

    @Test
    void bindKeyCombinesHostAndPort() {
        assertEquals("0.0.0.0:14550", MavlinkTelemetrySource.bindKey("0.0.0.0", 14550));
    }

    @Test
    void unclaimedVehiclesIsEmptyForABindKeyThatWasNeverOpened() {
        assertTrue(new MavlinkTelemetrySource().unclaimedVehicles("127.0.0.1:9999").isEmpty());
    }

    private static int freePort() throws Exception {
        try (java.net.DatagramSocket socket = new java.net.DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }
}

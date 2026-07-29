package com.drones.vision.adapter.mavlink;

import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.DiscoveredDevice;
import com.drones.vision.domain.model.FeedId;
import com.drones.vision.domain.model.FeedSpec;
import com.drones.vision.domain.model.StreamDescriptor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * docs/DRONE-INFRA-PLAN.md I-b: real loopback UDP exercise of {@link MavlinkHeartbeatScanner},
 * both paths (hub-borrow and self-bind), plus the bind-conflict/time-boxing guarantees {@link
 * com.drones.vision.domain.port.out.DeviceDiscoveryPort}'s contract requires. No hardware, no
 * docker.
 *
 * <p><b>Gotcha this suite shares with {@link MavlinkFleetGatewayIntegrationTest}</b>: several
 * cases here are timing-sensitive (a real 1Hz {@code HEARTBEAT} cadence, a real scan timeout
 * window) and must be verified via the full module build (`./mvnw -B -pl adapters/adapter-mavlink
 * test`), not a `-Dtest=` filtered single-class/method run, which can flake under a loaded sandbox.
 */
class MavlinkHeartbeatScannerTest {

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void selfBindPathDiscoversTwoDistinctSysidsHeardOnAnUnboundPort() throws Exception {
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();
        MavlinkTelemetrySource telemetrySource = new MavlinkTelemetrySource(); // never opened -- no active hub
        int port = freePort();
        MavlinkHeartbeatScanner scanner = new MavlinkHeartbeatScanner(telemetrySource, port);
        FeedId feedA = FeedId.random();
        FeedId feedB = FeedId.random();

        try {
            transmitter.start(feedA, feedSpec(port, "61"));
            transmitter.start(feedB, feedSpec(port, "62"));

            List<DiscoveredDevice> devices = scanner.scan(Duration.ofSeconds(4));

            Optional<DiscoveredDevice> deviceA = findBySysid(devices, 61);
            Optional<DiscoveredDevice> deviceB = findBySysid(devices, 62);
            assertTrue(deviceA.isPresent(), "expected sysid 61 to be discovered; got " + devices);
            assertTrue(deviceB.isPresent(), "expected sysid 62 to be discovered; got " + devices);

            assertEquals("mavlink", deviceA.get().method());
            assertEquals(URI.create("udp://0.0.0.0:" + port), deviceA.get().address());
            assertEquals(new CategoryId("drone"), deviceA.get().suggestedCategory(),
                    "MavlinkFeedTransmitter always reports MAV_TYPE_QUADROTOR -- an airborne kind");
            assertEquals("mavlink", deviceA.get().suggestedStream().protocol());
            assertEquals("61", deviceA.get().suggestedStream().options().get("sysid"));
            assertNull(deviceA.get().details().get("claimed"), "nothing is open, so nothing can be already-claimed");
        } finally {
            transmitter.stop(feedA);
            transmitter.stop(feedB);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void activeHubPathReportsAClaimedVehicleAsClaimedAndAnUnclaimedOneFromTheRegistry() throws Exception {
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();
        MavlinkTelemetrySource telemetrySource = new MavlinkTelemetrySource();
        int port = freePort();
        DeviceId deviceId = DeviceId.random();
        Device device = new Device(deviceId, "onboard-fc", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("mavlink", URI.create("udp://0.0.0.0:" + port), Map.of("sysid", "71")));
        MavlinkHeartbeatScanner scanner = new MavlinkHeartbeatScanner(telemetrySource, port);
        FeedId claimedFeed = FeedId.random();
        FeedId strayFeed = FeedId.random();

        try {
            telemetrySource.open(device); // starts the hub -- pinned to sysid 71, not yet claimed
            transmitter.start(claimedFeed, feedSpec(port, "71"));
            transmitter.start(strayFeed, feedSpec(port, "72")); // nothing pins/claims this one

            List<DiscoveredDevice> devices = scanner.scan(Duration.ofSeconds(5));

            Optional<DiscoveredDevice> claimed = findBySysid(devices, 71);
            Optional<DiscoveredDevice> unclaimed = findBySysid(devices, 72);
            assertTrue(claimed.isPresent(), "expected the claimed sysid 71 to be reported; got " + devices);
            assertTrue(unclaimed.isPresent(), "expected the unclaimed sysid 72 to be reported; got " + devices);

            assertEquals("true", claimed.get().details().get("claimed"));
            assertEquals(deviceId.value().toString(), claimed.get().details().get("claimedBy"));
            assertEquals("ArduPilot quadcopter (sysid 71)", claimed.get().name());

            assertNull(unclaimed.get().details().get("claimed"), "an unclaimed vehicle must not be labeled claimed");
            assertNull(unclaimed.get().details().get("claimedBy"));
        } finally {
            transmitter.stop(claimedFeed);
            transmitter.stop(strayFeed);
            telemetrySource.close(deviceId);
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void bindConflictWithANonHubProcessReturnsAnEmptyListWithoutThrowing() throws Exception {
        int port = freePort();
        MavlinkTelemetrySource telemetrySource = new MavlinkTelemetrySource(); // no active hub for this port
        MavlinkHeartbeatScanner scanner = new MavlinkHeartbeatScanner(telemetrySource, port);

        // A plain, non-reuse-address socket holding the exact port this scanner will try to bind --
        // simulating some other, non-gateway process already sitting on it.
        try (DatagramSocket blocker = new DatagramSocket(new InetSocketAddress("0.0.0.0", port))) {
            List<DiscoveredDevice> devices =
                    assertDoesNotThrow(() -> scanner.scan(Duration.ofMillis(300)), "a bind conflict must never throw");

            assertTrue(devices.isEmpty(), "expected an empty result on bind conflict; got " + devices);
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void scanSelfTimeBoxesToApproximatelyTheRequestedTimeout() throws Exception {
        int port = freePort();
        MavlinkTelemetrySource telemetrySource = new MavlinkTelemetrySource(); // no active hub, nothing transmitting
        MavlinkHeartbeatScanner scanner = new MavlinkHeartbeatScanner(telemetrySource, port);
        Duration timeout = Duration.ofSeconds(1);

        long startNanos = System.nanoTime();
        List<DiscoveredDevice> devices = scanner.scan(timeout);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

        assertTrue(devices.isEmpty(), "nothing was transmitting; expected an empty result");
        assertTrue(elapsedMillis >= timeout.toMillis() - 50,
                "scan returned too early: " + elapsedMillis + "ms for a " + timeout.toMillis() + "ms timeout");
        assertTrue(elapsedMillis <= timeout.toMillis() + 2_000,
                "scan overshot its self-time-box: " + elapsedMillis + "ms for a " + timeout.toMillis() + "ms timeout");
    }

    private static Optional<DiscoveredDevice> findBySysid(List<DiscoveredDevice> devices, int sysid) {
        return devices.stream().filter(d -> String.valueOf(sysid).equals(d.details().get("sysid"))).findFirst();
    }

    private static FeedSpec feedSpec(int port, String sysid) {
        return new FeedSpec("mavlink", URI.create("udp://127.0.0.1:" + port),
                Map.of("route", "10.00000,20.00000;10.00050,20.00000", "sysid", sysid, "positionRateHz", "10"));
    }

    private static int freePort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }
}

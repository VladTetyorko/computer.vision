package com.drones.vision.adapter.mavlink;

import com.drones.mavlink.CompId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.codec.FrameReader;
import com.drones.mavlink.codec.FrameWriter;
import com.drones.mavlink.transport.ByteChunk;
import com.drones.mavlink.transport.CarrierKind;
import com.drones.mavlink.transport.LinkDescriptor;
import com.drones.mavlink.transport.LinkId;
import com.drones.mavlink.transport.LinkPeer;
import com.drones.mavlink.transport.MavlinkLink;
import com.drones.mavlink.transport.SerialRole;
import com.drones.mavlink.transport.UdpTargetLink;

import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.perception.domain.model.FeedId;
import com.drones.vision.perception.domain.model.FeedSpec;
import com.drones.vision.warehouse.domain.model.DiscoveredDevice;
import com.drones.vision.warehouse.domain.model.Device;

import io.dronefleet.mavlink.minimal.Heartbeat;
import io.dronefleet.mavlink.minimal.MavAutopilot;
import io.dronefleet.mavlink.minimal.MavModeFlag;
import io.dronefleet.mavlink.minimal.MavState;
import io.dronefleet.mavlink.minimal.MavType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §11 Z2b: the standing, claim-free MAVLink
 * lobby ({@link MavlinkTelemetrySource#holdLobby(int)}/{@link MavlinkTelemetrySource#releaseLobby(int)})
 * and the GCS heartbeat reply it arms on {@link MavlinkGateway}. Real-loopback throughout, same
 * style as {@link MavlinkFleetGatewayIntegrationTest}/{@link MavlinkConnectRemediationIntegrationTest} —
 * a hand-fake vehicle speaking just enough MAVLink to prove the actual wire behaviour, not a mock of
 * this module's own classes.
 */
class MavlinkLobbyHoldTest {

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void holdThenOpenDeviceShareOneGatewayAndTheDevicesClaimWorks() throws Exception {
        MavlinkTelemetrySource source = new MavlinkTelemetrySource();
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();
        int port = freePort();
        String bindKey = MavlinkTelemetrySource.bindKey(MavlinkTelemetrySource.DEFAULT_BIND_HOST, port);
        DeviceId deviceId = DeviceId.random();
        FeedId feedId = FeedId.random();

        try {
            source.holdLobby(port);
            MavlinkGateway heldGateway = source.gateway(bindKey);
            assertNotNull(heldGateway, "holdLobby must create a gateway for this bind address");

            source.open(device(port, deviceId, Map.of("sysid", "101")));
            assertSame(heldGateway, source.gateway(bindKey),
                    "open() for the same bind address must reuse the lobby's gateway, not build a second one");

            transmitter.start(feedId, feedSpec(port, "101", route(1)));
            MavlinkGateway.ClaimedVehicle claimed = awaitClaimed(source, bindKey, 101, Duration.ofSeconds(15));
            assertEquals(deviceId, claimed.deviceId(), "the device's pinned claim must work while the lobby holds the same gateway");
        } finally {
            transmitter.stop(feedId);
            source.close(deviceId);
            source.releaseLobby(port);
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void openDeviceThenHoldSharesTheSameGateway() throws Exception {
        MavlinkTelemetrySource source = new MavlinkTelemetrySource();
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();
        int port = freePort();
        String bindKey = MavlinkTelemetrySource.bindKey(MavlinkTelemetrySource.DEFAULT_BIND_HOST, port);
        DeviceId deviceId = DeviceId.random();
        FeedId feedId = FeedId.random();

        try {
            source.open(device(port, deviceId, Map.of("sysid", "102")));
            MavlinkGateway openedGateway = source.gateway(bindKey);
            assertNotNull(openedGateway);

            source.holdLobby(port);
            assertSame(openedGateway, source.gateway(bindKey),
                    "holdLobby() for an already-open bind address must reuse the device's gateway, not build a second one");
            assertTrue(openedGateway.isLobbyHeld());

            transmitter.start(feedId, feedSpec(port, "102", route(2)));
            MavlinkGateway.ClaimedVehicle claimed = awaitClaimed(source, bindKey, 102, Duration.ofSeconds(15));
            assertEquals(deviceId, claimed.deviceId());
        } finally {
            transmitter.stop(feedId);
            source.close(deviceId);
            source.releaseLobby(port);
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void releaseWithZeroDevicesClosesTheGateway() throws Exception {
        MavlinkTelemetrySource source = new MavlinkTelemetrySource();
        int port = freePort();
        String bindKey = MavlinkTelemetrySource.bindKey(MavlinkTelemetrySource.DEFAULT_BIND_HOST, port);

        source.holdLobby(port);
        assertTrue(source.hasActiveHub(bindKey), "the hold must bind and register an active gateway");

        source.releaseLobby(port);

        assertFalse(source.hasActiveHub(bindKey), "a lobby hold with zero devices must close on release");
        assertDoesNotThrow(() -> {
            try (DatagramSocket probe = new DatagramSocket(null)) {
                probe.setReuseAddress(true);
                probe.bind(new InetSocketAddress(MavlinkTelemetrySource.DEFAULT_BIND_HOST, port));
            }
        }, "the underlying socket must actually be released, not merely marked closed internally");
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void releaseWithALiveDeviceDoesNotCloseTheGateway() throws Exception {
        MavlinkTelemetrySource source = new MavlinkTelemetrySource();
        int port = freePort();
        String bindKey = MavlinkTelemetrySource.bindKey(MavlinkTelemetrySource.DEFAULT_BIND_HOST, port);
        DeviceId deviceId = DeviceId.random();

        try {
            source.holdLobby(port);
            source.open(device(port, deviceId, Map.of()));
            MavlinkGateway gateway = source.gateway(bindKey);

            source.releaseLobby(port);

            assertTrue(source.hasActiveHub(bindKey),
                    "a live device registration must keep the gateway alive across a lobby release");
            assertSame(gateway, source.gateway(bindKey),
                    "releasing the lobby must not replace or evict a gateway a device still uses");
            assertFalse(gateway.isLobbyHeld(), "the hold flag itself must still clear even though the gateway stays open");
        } finally {
            source.close(deviceId);
        }
    }

    @Test
    @Timeout(value = 25, unit = TimeUnit.SECONDS)
    void lobbyNeverClaimsAHeardSysidStaysUnclaimedUntilARealDeviceClaimsIt() throws Exception {
        MavlinkTelemetrySource source = new MavlinkTelemetrySource();
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();
        int port = freePort();
        String bindKey = MavlinkTelemetrySource.bindKey(MavlinkTelemetrySource.DEFAULT_BIND_HOST, port);
        FeedId feedId = FeedId.random();
        DeviceId deviceId = DeviceId.random();

        try {
            source.holdLobby(port);
            transmitter.start(feedId, feedSpec(port, "121", route(3)));

            awaitUnclaimed(source, bindKey, 121, Duration.ofSeconds(15));
            // A couple more heartbeat periods -- a lobby hold registers no claim of its own, ever.
            Thread.sleep(1000);
            assertTrue(source.unclaimedVehicles(bindKey).stream().anyMatch(v -> v.sysid() == 121));
            assertTrue(source.claimedVehicles(bindKey).isEmpty(), "a lobby hold alone must never claim anything");

            source.open(device(port, deviceId, Map.of())); // an unpinned real device
            awaitClaimed(source, bindKey, 121, Duration.ofSeconds(15));
            assertTrue(source.unclaimedVehicles(bindKey).stream().noneMatch(v -> v.sysid() == 121),
                    "once a real device claims it, the sysid must leave the unclaimed registry");
        } finally {
            transmitter.stop(feedId);
            source.close(deviceId);
            source.releaseLobby(port);
        }
    }

    @Test
    @Timeout(value = 25, unit = TimeUnit.SECONDS)
    void holdLobbyHealsAGatewayThatHasAlreadyClosed() throws Exception {
        MavlinkTelemetrySource source = new MavlinkTelemetrySource();
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();
        int port = freePort();
        String bindKey = MavlinkTelemetrySource.bindKey(MavlinkTelemetrySource.DEFAULT_BIND_HOST, port);
        FeedId feedId = FeedId.random();

        try {
            source.holdLobby(port);
            MavlinkGateway firstGateway = source.gateway(bindKey);
            source.releaseLobby(port); // zero devices -> genuinely closes and is evicted (see the test above)
            assertFalse(source.hasActiveHub(bindKey));
            assertTrue(firstGateway.isClosed());

            // A later hold on the exact same port must find the closed instance and replace it with a
            // fresh, working one -- not resurrect the closed one and not silently do nothing.
            source.holdLobby(port);
            MavlinkGateway healedGateway = source.gateway(bindKey);
            assertNotNull(healedGateway);
            assertTrue(healedGateway != firstGateway, "healing must build a new gateway instance, not reuse the closed one");
            assertTrue(source.hasActiveHub(bindKey));

            transmitter.start(feedId, feedSpec(port, "131", route(4)));
            awaitUnclaimed(source, bindKey, 131, Duration.ofSeconds(15));
        } finally {
            transmitter.stop(feedId);
            source.releaseLobby(port);
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void aGenuineLinkFailureClosesTheGatewayRegardlessOfTheLobbyHoldAndStopsItsHeartbeatScheduler() throws Exception {
        FailingLink link = new FailingLink();
        MavlinkGateway gateway = new MavlinkGateway(MavlinkSettings.defaults());
        gateway.register(link, new LinkDescriptor(CarrierKind.SERIAL, SerialRole.NONE, "lobby-hold-failing-test-link", 0));
        try {
            gateway.holdLobby();
            assertTrue(gateway.isLobbyHeld());
            assertFalse(gateway.isClosed());

            link.fail(); // simulates the socket dying -- a genuine poll() IOException

            long deadline = System.currentTimeMillis() + 5000;
            while (!gateway.isClosed() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertTrue(gateway.isClosed(),
                    "a genuine link failure must close the gateway even while the lobby holds it open");
        } finally {
            gateway.close(); // idempotent no-op if the failure already closed it
        }
    }

    @Test
    @Timeout(value = 25, unit = TimeUnit.SECONDS)
    void heartbeatSchedulerStartsOnHoldAndStopsOnRelease() throws Exception {
        MavlinkTelemetrySource source = new MavlinkTelemetrySource();
        int port = freePort();
        DeviceId deviceId = DeviceId.random();

        try {
            source.open(device(port, deviceId, Map.of())); // keeps the gateway alive independent of the hold
            source.holdLobby(port);

            try (Announcer vehicle = Announcer.connect(port, 141)) {
                vehicle.announce(); // the gateway must now know this peer's link -- HeartbeatService can address it
                assertTrue(vehicle.awaitGcsHeartbeat(Duration.ofSeconds(3)),
                        "a held lobby must transmit a GCS heartbeat once a peer is known");

                source.releaseLobby(port);
                String bindKey = MavlinkTelemetrySource.bindKey(MavlinkTelemetrySource.DEFAULT_BIND_HOST, port);
                assertTrue(source.hasActiveHub(bindKey), "the device registration keeps the gateway itself alive across this release");

                vehicle.awaitGcsHeartbeat(Duration.ofMillis(500)); // drain anything already in flight at release time
                assertFalse(vehicle.awaitGcsHeartbeat(Duration.ofSeconds(3)),
                        "releasing the lobby must stop the heartbeat TX even though the gateway itself stays open");
            }
        } finally {
            source.close(deviceId);
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void scannerTakesTheHubBorrowPathWhenTheLobbyIsHeld() throws Exception {
        MavlinkTelemetrySource source = new MavlinkTelemetrySource();
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();
        int port = freePort();
        String bindKey = MavlinkTelemetrySource.bindKey(MavlinkTelemetrySource.DEFAULT_BIND_HOST, port);
        FeedId feedId = FeedId.random();

        try {
            source.holdLobby(port);
            assertTrue(source.hasActiveHub(bindKey),
                    "a held lobby must count as an active hub -- this is exactly what steers the scanner onto the hub-borrow path");

            transmitter.start(feedId, feedSpec(port, "151", route(5)));
            awaitUnclaimed(source, bindKey, 151, Duration.ofSeconds(15));

            MavlinkHeartbeatScanner scanner = new MavlinkHeartbeatScanner(source, port);
            List<DiscoveredDevice> found = scanner.scan(Duration.ofSeconds(2));

            assertTrue(found.stream().anyMatch(
                            d -> "151".equals(d.suggestedStream().options().get(MavlinkTelemetrySource.OPTION_SYSID))),
                    "the scanner must have borrowed the held gateway's socket (hub path) -- a self-bind attempt would "
                            + "have hit a bind conflict against the lobby's own socket and returned nothing");
        } finally {
            transmitter.stop(feedId);
            source.releaseLobby(port);
        }
    }

    private static Device device(int port, DeviceId id, Map<String, String> options) {
        return new Device(id, "lobby-hold-test-device", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("mavlink",
                        URI.create("udp://" + MavlinkTelemetrySource.DEFAULT_BIND_HOST + ":" + port), options));
    }

    private static FeedSpec feedSpec(int port, String sysid, String route) {
        return new FeedSpec("mavlink", URI.create("udp://127.0.0.1:" + port),
                Map.of("route", route, "sysid", sysid, "positionRateHz", "20", "speedMps", "5"));
    }

    /** Bucket {@code n}'s route: {@code n0.00000,30.00000 -> n0.00050,30.00000} -- unused by these tests beyond feeding the transmitter a valid route. */
    private static String route(int bucket) {
        return bucket + "0.00000,30.00000;" + bucket + "0.00050,30.00000";
    }

    private static int freePort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static MavlinkGateway.UnclaimedVehicle awaitUnclaimed(
            MavlinkTelemetrySource source, String bindKey, int sysid, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            for (MavlinkGateway.UnclaimedVehicle vehicle : source.unclaimedVehicles(bindKey)) {
                if (vehicle.sysid() == sysid) {
                    return vehicle;
                }
            }
            Thread.sleep(150);
        }
        throw new AssertionError("expected sysid " + sysid + " in the unclaimed registry within " + timeout
                + "; got " + source.unclaimedVehicles(bindKey));
    }

    private static MavlinkGateway.ClaimedVehicle awaitClaimed(
            MavlinkTelemetrySource source, String bindKey, int sysid, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            for (MavlinkGateway.ClaimedVehicle vehicle : source.claimedVehicles(bindKey)) {
                if (vehicle.sysid() == sysid) {
                    return vehicle;
                }
            }
            Thread.sleep(150);
        }
        throw new AssertionError("expected sysid " + sysid + " claimed within " + timeout
                + "; got claimed=" + source.claimedVehicles(bindKey) + " unclaimed=" + source.unclaimedVehicles(bindKey));
    }

    /**
     * A minimal test double speaking just enough MAVLink to stand in for a vehicle that has just
     * locked onto this port: sends one (or more) {@code HEARTBEAT}s, and can wait for the GCS {@code
     * HEARTBEAT} reply {@link MavlinkGateway#holdLobby()} arms. Deliberately reimplemented rather
     * than shared with {@link MavlinkConnectRemediationIntegrationTest}'s own private {@code
     * FakeVehicle} -- this module's existing convention for this exact helper, not an oversight.
     */
    private static final class Announcer implements AutoCloseable {
        private final UdpTargetLink link;
        private final FrameWriter writer;
        private final FrameReader reader;

        private Announcer(UdpTargetLink link, FrameWriter writer) {
            this.link = link;
            this.writer = writer;
            this.reader = new FrameReader(link);
        }

        static Announcer connect(int gatewayPort, int sysid) throws IOException {
            UdpTargetLink link = new UdpTargetLink("127.0.0.1", gatewayPort);
            FrameWriter writer = new FrameWriter(new SysId(sysid), new CompId(MavlinkFlightCommander.TARGET_COMPONENT_AUTOPILOT));
            writer.addLink(link);
            return new Announcer(link, writer);
        }

        void announce() {
            Heartbeat heartbeat = Heartbeat.builder()
                    .type(MavType.MAV_TYPE_QUADROTOR)
                    .autopilot(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA)
                    .baseMode(MavModeFlag.MAV_MODE_FLAG_SAFETY_ARMED, MavModeFlag.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED)
                    .customMode(0)
                    .systemStatus(MavState.MAV_STATE_ACTIVE)
                    .mavlinkVersion(3)
                    .build();
            writer.broadcast(heartbeat, link.id());
        }

        /** Blocks (bounded by {@code timeout}) for a {@code HEARTBEAT} reply from sysid 255 (the gateway's own GCS identity). */
        boolean awaitGcsHeartbeat(Duration timeout) throws IOException {
            long deadlineNanos = System.nanoTime() + timeout.toNanos();
            while (true) {
                long remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                if (remainingMillis <= 0) {
                    return false;
                }
                ByteChunk chunk = link.poll(Duration.ofMillis(remainingMillis));
                if (chunk == null) {
                    continue;
                }
                boolean[] found = {false};
                reader.offer(chunk, frame -> {
                    if (frame.is(Heartbeat.class) && frame.header().system().value() == 255) {
                        found[0] = true;
                    }
                });
                if (found[0]) {
                    return true;
                }
            }
        }

        @Override
        public void close() {
            link.close();
        }
    }

    /**
     * A conforming {@link MavlinkLink} whose {@link #poll} behaves exactly like a real link with
     * nothing to say (returns {@code null} on every timeout) until {@link #fail()} is called, at
     * which point the next {@code poll()} throws a genuine {@link IOException} -- the same "socket
     * dies mid-flight" double {@link MavlinkGatewayLinkFailureTest} uses, reimplemented here rather
     * than shared (both are private, package-scoped test doubles with no third caller).
     */
    private static final class FailingLink implements MavlinkLink {
        private final LinkId id = new LinkId("lobby-hold-failing-test-link");
        private final CountDownLatch failGate = new CountDownLatch(1);
        private final IOException failure = new IOException("simulated read failure");

        @Override
        public LinkId id() {
            return id;
        }

        @Override
        public boolean preservesMessageBoundaries() {
            return true;
        }

        @Override
        public ByteChunk poll(Duration timeout) throws IOException {
            try {
                if (failGate.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw failure;
                }
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        @Override
        public void send(byte[] frame, int off, int len, LinkPeer target) {
            // not exercised by this test
        }

        @Override
        public LinkPeer defaultTarget() {
            return LinkPeer.NONE;
        }

        @Override
        public void close() {
            // deliberately independent of failGate -- this test controls exactly when poll() fails
        }

        void fail() {
            failGate.countDown();
        }
    }
}

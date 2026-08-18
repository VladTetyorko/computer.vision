package com.drones.vision.adapter.mavlink;

import com.drones.vision.kernel.Capability;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.perception.domain.model.FeedId;
import com.drones.vision.perception.domain.model.FeedSpec;
import com.drones.vision.kernel.StreamDescriptor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramSocket;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The required real-loopback proof of {@link MavlinkMessageInventory}'s per-sysid isolation
 * (docs/plans/active/DRONE-ONBOARDING-PLAN.md O1): two genuine {@link MavlinkFeedTransmitter} feeds,
 * distinct system ids, one real bound {@link MavlinkGateway} socket — exactly the scenario {@link
 * MavlinkFleetGatewayIntegrationTest} already exercises for claim routing, reused here for the
 * inventory. {@link MavlinkMessageInventoryTest} covers the counting/windowing/eviction arithmetic
 * itself with fast hand-fake frames; this class proves that arithmetic holds over the real socket
 * path two physically distinct vehicles would actually use.
 */
class MavlinkMessageInventoryIntegrationTest {

    private static final int HEARTBEAT_MSG_ID = 0; // io.dronefleet.mavlink.minimal.Heartbeat
    private static final int GLOBAL_POSITION_INT_MSG_ID = 33; // io.dronefleet.mavlink.common.GlobalPositionInt

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void twoPeersOnOneLinkNeverPoolTheirMessageCounts() throws Exception {
        MavlinkSettings settings = MavlinkSettings.defaults().withInventory(
                new MavlinkSettings.Inventory(Duration.ofSeconds(4), Duration.ofMillis(500), 64, 128));
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();
        MavlinkTelemetrySource source = new MavlinkTelemetrySource(settings);
        int port = freePort();
        FeedId feedA = FeedId.random();
        FeedId feedB = FeedId.random();
        DeviceId deviceId = DeviceId.random();
        String bindKey = MavlinkTelemetrySource.bindKey("127.0.0.1", port);
        double rateA = 30.0;
        double rateB = 6.0;

        try {
            // One device is enough to create the shared gateway; the inventory hears every sysid on
            // the socket regardless of which one this device claims -- passive inventory,
            // independent of VehicleClaimPolicy (see MavlinkMessageInventory's own javadoc).
            source.open(device(port, deviceId, Map.of("sysid", "71")));

            transmitter.start(feedA, feedSpec(port, "71", route(1), rateA));
            transmitter.start(feedB, feedSpec(port, "72", route(2), rateB));

            MavlinkMessageInventory inventory = source.gateway(bindKey).messageInventory();

            MavlinkMessageInventory.PeerSnapshot snapshotA =
                    awaitSnapshotWithMessage(inventory, 71, GLOBAL_POSITION_INT_MSG_ID, Duration.ofSeconds(15));
            MavlinkMessageInventory.PeerSnapshot snapshotB =
                    awaitSnapshotWithMessage(inventory, 72, GLOBAL_POSITION_INT_MSG_ID, Duration.ofSeconds(15));

            assertEquals(71, snapshotA.sysid());
            assertEquals(72, snapshotB.sysid());
            assertEquals(Set.of(71, 72), Set.copyOf(inventory.observedPeers()));

            double hzA = hzOf(snapshotA, GLOBAL_POSITION_INT_MSG_ID);
            double hzB = hzOf(snapshotB, GLOBAL_POSITION_INT_MSG_ID);

            // The proof of isolation: each peer's own rate, never the other's and never their sum
            // -- pooling into one shared counter would make both read the same blended number.
            assertTrue(hzA > hzB,
                    "sysid 71's " + rateA + " Hz feed must read faster than sysid 72's " + rateB + " Hz feed, got "
                            + hzA + " vs " + hzB);
            assertTrue(hzA < rateA + rateB, "sysid 71's rate must not include sysid 72's traffic, got " + hzA);
            assertTrue(hzB < rateA + rateB, "sysid 72's rate must not include sysid 71's traffic, got " + hzB);

            // HEARTBEAT/SYS_STATUS/GPS_RAW_INT arrive at the same 1 Hz from every feed regardless of
            // positionRateHz -- proves whole-message-set isolation, not just the varied-rate one.
            assertTrue(countOf(snapshotA, HEARTBEAT_MSG_ID) > 0, "sysid 71 must have its own HEARTBEAT count");
            assertTrue(countOf(snapshotB, HEARTBEAT_MSG_ID) > 0, "sysid 72 must have its own HEARTBEAT count");

            assertTrue(snapshotA.bytesPerSecond() > 0, "sysid 71 must have a positive bytes/s estimate");
            assertTrue(snapshotB.bytesPerSecond() > 0, "sysid 72 must have a positive bytes/s estimate");
            assertTrue(snapshotA.bytesPerSecond() > snapshotB.bytesPerSecond(),
                    "sysid 71 sends GLOBAL_POSITION_INT five times as often as sysid 72, so its bytes/s estimate "
                            + "must be higher too, got " + snapshotA.bytesPerSecond() + " vs " + snapshotB.bytesPerSecond());
        } finally {
            transmitter.stop(feedA);
            transmitter.stop(feedB);
            source.close(deviceId);
        }
    }

    private static Device device(int port, DeviceId id, Map<String, String> options) {
        return new Device(id, "message-inventory-test-device", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("mavlink", URI.create("udp://127.0.0.1:" + port), options));
    }

    private static FeedSpec feedSpec(int port, String sysid, String route, double positionRateHz) {
        return new FeedSpec("mavlink", URI.create("udp://127.0.0.1:" + port),
                Map.of("route", route, "sysid", sysid, "positionRateHz", String.valueOf(positionRateHz), "speedMps", "5"));
    }

    /** Bucket {@code n}'s route: {@code n0.00000,30.00000 -> n0.00050,30.00000} -- unused by these assertions, kept distinct for readability only. */
    private static String route(int bucket) {
        return bucket + "0.00000,30.00000;" + bucket + "0.00050,30.00000";
    }

    private static int freePort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static MavlinkMessageInventory.PeerSnapshot awaitSnapshotWithMessage(
            MavlinkMessageInventory inventory, int sysid, int messageId, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        MavlinkMessageInventory.PeerSnapshot last = null;
        while (System.currentTimeMillis() < deadline) {
            last = inventory.snapshot(sysid);
            if (last != null && last.messages().stream().anyMatch(m -> m.messageId() == messageId && m.count() > 0)) {
                return last;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("expected sysid " + sysid + " to report message id " + messageId
                + " within " + timeout + "; last snapshot was " + last);
    }

    private static long countOf(MavlinkMessageInventory.PeerSnapshot snapshot, int messageId) {
        return snapshot.messages().stream().filter(m -> m.messageId() == messageId)
                .findFirst().map(MavlinkMessageInventory.MessageRate::count)
                .orElseThrow(() -> new AssertionError("no observation for message id " + messageId + " in " + snapshot));
    }

    private static double hzOf(MavlinkMessageInventory.PeerSnapshot snapshot, int messageId) {
        return snapshot.messages().stream().filter(m -> m.messageId() == messageId)
                .findFirst().map(MavlinkMessageInventory.MessageRate::hz)
                .orElseThrow(() -> new AssertionError("no observation for message id " + messageId + " in " + snapshot));
    }
}

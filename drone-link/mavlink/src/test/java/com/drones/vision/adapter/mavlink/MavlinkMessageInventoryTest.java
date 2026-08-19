package com.drones.vision.adapter.mavlink;

import com.drones.mavlink.CompId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.codec.MavFrame;
import com.drones.mavlink.codec.MavHeader;
import com.drones.mavlink.session.Dispatcher;
import com.drones.mavlink.session.MessageFilter;
import com.drones.mavlink.session.Subscription;
import com.drones.mavlink.transport.LinkId;
import com.drones.mavlink.transport.LinkPeer;

import io.dronefleet.mavlink.common.GlobalPositionInt;
import io.dronefleet.mavlink.minimal.Heartbeat;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast, deterministic coverage of {@link MavlinkMessageInventory}'s counting/windowing/eviction
 * arithmetic, fed frames directly through a hand-fake {@link Dispatcher} — no socket, no thread, no
 * wall-clock dependency for the correctness assertions (only {@link #decaysToZeroOnceTheWindowPasses}
 * needs real time to elapse, kept short). {@link MavlinkMessageInventoryIntegrationTest} is the
 * required real-loopback proof that this arithmetic holds over an actual UDP link with two genuine
 * peers (docs/plans/active/DRONE-ONBOARDING-PLAN.md O1's "a new loopback test must prove
 * [per-sysid isolation]").
 */
class MavlinkMessageInventoryTest {

    private static final int HEARTBEAT_MSG_ID = 0;
    private static final int GLOBAL_POSITION_INT_MSG_ID = 33;

    @Test
    void twoSysidsOnOneLinkNeverPoolTheirMessageCounts() {
        FakeDispatcher dispatcher = new FakeDispatcher();
        MavlinkMessageInventory inventory = new MavlinkMessageInventory(dispatcher, generousWindow());

        Instant now = Instant.now();
        dispatcher.feed(heartbeatFrame(11, now));
        dispatcher.feed(heartbeatFrame(11, now));
        dispatcher.feed(heartbeatFrame(11, now));
        dispatcher.feed(heartbeatFrame(22, now));
        dispatcher.feed(heartbeatFrame(22, now));

        MavlinkMessageInventory.PeerSnapshot snapshot11 = inventory.snapshot(11);
        MavlinkMessageInventory.PeerSnapshot snapshot22 = inventory.snapshot(22);

        assertEquals(3, countOf(snapshot11, HEARTBEAT_MSG_ID), "sysid 11 must see only its own 3 heartbeats");
        assertEquals(2, countOf(snapshot22, HEARTBEAT_MSG_ID), "sysid 22 must see only its own 2 heartbeats, never 3+2=5");
        assertEquals(List.of(11, 22), List.copyOf(inventory.observedPeers()).stream().sorted().toList());
    }

    @Test
    void hzIsTheWindowedCountDividedByTheConfiguredWindow() {
        FakeDispatcher dispatcher = new FakeDispatcher();
        MavlinkSettings.Inventory settings = new MavlinkSettings.Inventory(
                Duration.ofSeconds(4), Duration.ofSeconds(1), 64, 128);
        MavlinkMessageInventory inventory = new MavlinkMessageInventory(dispatcher, settings);

        Instant now = Instant.now();
        for (int i = 0; i < 8; i++) {
            dispatcher.feed(heartbeatFrame(1, now));
        }

        MavlinkMessageInventory.MessageRate rate = rateOf(inventory.snapshot(1), HEARTBEAT_MSG_ID);
        assertEquals(8, rate.count());
        assertEquals(2.0, rate.hz(), 0.0001, "8 messages over a 4s window is exactly 2 Hz");
    }

    @Test
    void aSysidNeverObservedReturnsNoSnapshot() {
        MavlinkMessageInventory inventory = new MavlinkMessageInventory(new FakeDispatcher(), generousWindow());
        assertNull(inventory.snapshot(99));
        assertTrue(inventory.observedPeers().isEmpty());
    }

    @Test
    void bytesPerSecondEstimateGrowsWithPayloadSize() {
        // A window equal to exactly one bucket makes bytesPerSecond() numerically equal to the raw
        // byte estimate for whatever arrived in this one second, so a single fed frame's own size
        // is directly observable rather than crushed to a rounding artifact by a longer window.
        FakeDispatcher dispatcher = new FakeDispatcher();
        MavlinkMessageInventory inventory = new MavlinkMessageInventory(dispatcher, oneSecondWindow());

        Instant now = Instant.now();
        dispatcher.feed(heartbeatFrame(1, now)); // small, fixed payload
        dispatcher.feed(globalPositionIntFrame(2, now, false)); // larger payload, unsigned

        long bytesHeartbeatPeer = inventory.snapshot(1).bytesPerSecond();
        long bytesPositionPeer = inventory.snapshot(2).bytesPerSecond();

        // 9/28-byte payloads (the sum of each message's own @MavlinkFieldInfo fields) + the fixed
        // 12-byte unsigned-v2 frame overhead (10-byte header + 2-byte CRC, no signature block).
        assertEquals(21, bytesHeartbeatPeer, "HEARTBEAT: 9-byte payload + 12-byte v2 overhead");
        assertEquals(40, bytesPositionPeer, "GLOBAL_POSITION_INT: 28-byte payload + 12-byte v2 overhead");
        assertTrue(bytesPositionPeer > bytesHeartbeatPeer,
                "GLOBAL_POSITION_INT must estimate more bytes/s than HEARTBEAT, got "
                        + bytesPositionPeer + " vs " + bytesHeartbeatPeer);
    }

    @Test
    void aSignedV2FrameEstimatesExactlyTheSignatureBlockMoreThanAnUnsignedOne() {
        FakeDispatcher dispatcher = new FakeDispatcher();
        MavlinkMessageInventory inventory = new MavlinkMessageInventory(dispatcher, oneSecondWindow());

        Instant now = Instant.now();
        dispatcher.feed(globalPositionIntFrame(1, now, false));
        dispatcher.feed(globalPositionIntFrame(2, now, true));

        long unsigned = inventory.snapshot(1).bytesPerSecond();
        long signed = inventory.snapshot(2).bytesPerSecond();

        // 13 bytes is MAVLink 2's own signature block size (link id + 6-byte timestamp + 6-byte
        // signature) -- a protocol constant, not a value this test invented.
        assertEquals(13, signed - unsigned, "a signed v2 frame must cost exactly the signature block more");
    }

    @Test
    void trackedPeersAreBoundedAndLeastRecentlyObservedIsEvictedFirst() {
        FakeDispatcher dispatcher = new FakeDispatcher();
        MavlinkSettings.Inventory settings = new MavlinkSettings.Inventory(
                Duration.ofSeconds(10), Duration.ofSeconds(1), 2, 128);
        MavlinkMessageInventory inventory = new MavlinkMessageInventory(dispatcher, settings);

        Instant now = Instant.now();
        dispatcher.feed(heartbeatFrame(1, now));
        dispatcher.feed(heartbeatFrame(2, now));
        dispatcher.feed(heartbeatFrame(3, now)); // pushes sysid 1 (least recently touched) out at cap 2

        assertNull(inventory.snapshot(1), "sysid 1 must have been evicted once the tracked-peer cap was exceeded");
        assertEquals(1, countOf(inventory.snapshot(2), HEARTBEAT_MSG_ID));
        assertEquals(1, countOf(inventory.snapshot(3), HEARTBEAT_MSG_ID));
    }

    @Test
    void trackedMessageTypesPerPeerAreBoundedTheSameWay() {
        FakeDispatcher dispatcher = new FakeDispatcher();
        MavlinkSettings.Inventory settings = new MavlinkSettings.Inventory(
                Duration.ofSeconds(10), Duration.ofSeconds(1), 64, 2);
        MavlinkMessageInventory inventory = new MavlinkMessageInventory(dispatcher, settings);

        Instant now = Instant.now();
        dispatcher.feed(frame(1, 100, Heartbeat.builder().build(), now, 2, false));
        dispatcher.feed(frame(1, 101, Heartbeat.builder().build(), now, 2, false));
        dispatcher.feed(frame(1, 102, Heartbeat.builder().build(), now, 2, false));

        assertEquals(2, inventory.snapshot(1).messages().size(),
                "message id 100 must have been evicted once the tracked-type cap was exceeded");
    }

    @Test
    void decaysToZeroOnceTheWindowPasses() throws InterruptedException {
        FakeDispatcher dispatcher = new FakeDispatcher();
        MavlinkSettings.Inventory settings = new MavlinkSettings.Inventory(
                Duration.ofMillis(200), Duration.ofMillis(50), 64, 128);
        MavlinkMessageInventory inventory = new MavlinkMessageInventory(dispatcher, settings);

        dispatcher.feed(heartbeatFrame(1, Instant.now()));
        assertTrue(countOf(inventory.snapshot(1), HEARTBEAT_MSG_ID) > 0, "must be counted immediately after arrival");

        Thread.sleep(350); // well past the 200ms window; no new frame arrives to trigger this

        MavlinkMessageInventory.MessageRate decayed = rateOf(inventory.snapshot(1), HEARTBEAT_MSG_ID);
        assertEquals(0, decayed.count(), "a peer gone silent for longer than the window must decay to zero, "
                + "never keep reporting a frozen last-known rate");
        assertEquals(0.0, decayed.hz(), 0.0001);
    }

    private static MavlinkSettings.Inventory generousWindow() {
        return new MavlinkSettings.Inventory(Duration.ofSeconds(30), Duration.ofSeconds(1), 64, 128);
    }

    /** One bucket, one second: {@code bytesPerSecond()}/{@code hz()} equal the raw windowed sum, unscaled. */
    private static MavlinkSettings.Inventory oneSecondWindow() {
        return new MavlinkSettings.Inventory(Duration.ofSeconds(1), Duration.ofSeconds(1), 64, 128);
    }

    private static MavFrame heartbeatFrame(int sysid, Instant receivedAt) {
        return frame(sysid, HEARTBEAT_MSG_ID, Heartbeat.builder().build(), receivedAt, 2, false);
    }

    private static MavFrame globalPositionIntFrame(int sysid, Instant receivedAt, boolean signed) {
        GlobalPositionInt payload = GlobalPositionInt.builder()
                .timeBootMs(0L).lat(0).lon(0).alt(0).relativeAlt(0).vx(0).vy(0).vz(0).hdg(0).build();
        return frame(sysid, GLOBAL_POSITION_INT_MSG_ID, payload, receivedAt, 2, signed);
    }

    private static MavFrame frame(int sysid, int messageId, Object payload, Instant receivedAt, int version, boolean signed) {
        MavHeader header = new MavHeader(version, 0, new SysId(sysid), new CompId(1), messageId, 0, 0, signed);
        return new MavFrame(header, payload, new LinkId("test-link"), new LinkPeer("127.0.0.1", 14550), receivedAt);
    }

    private static long countOf(MavlinkMessageInventory.PeerSnapshot snapshot, int messageId) {
        return rateOf(snapshot, messageId).count();
    }

    private static MavlinkMessageInventory.MessageRate rateOf(MavlinkMessageInventory.PeerSnapshot snapshot, int messageId) {
        return snapshot.messages().stream()
                .filter(rate -> rate.messageId() == messageId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no observation for message id " + messageId + " in " + snapshot));
    }

    /** Captures the one handler {@link MavlinkGateway}/{@link MavlinkMessageInventory} would register, so a test can feed frames directly without a real socket. */
    private static final class FakeDispatcher implements Dispatcher {
        private Consumer<MavFrame> handler;

        @Override
        public Subscription subscribe(MessageFilter filter, Consumer<MavFrame> handler) {
            this.handler = handler;
            return () -> { };
        }

        void feed(MavFrame frame) {
            handler.accept(frame);
        }
    }
}

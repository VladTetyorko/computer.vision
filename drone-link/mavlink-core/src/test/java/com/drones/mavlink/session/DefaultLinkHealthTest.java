package com.drones.mavlink.session;

import com.drones.mavlink.CompId;
import com.drones.mavlink.PeerId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.codec.MavFrame;
import com.drones.mavlink.codec.MavHeader;
import com.drones.mavlink.transport.LinkId;
import com.drones.mavlink.transport.LinkPeer;

import io.dronefleet.mavlink.minimal.Heartbeat;
import io.dronefleet.mavlink.minimal.MavAutopilot;
import io.dronefleet.mavlink.minimal.MavModeFlag;
import io.dronefleet.mavlink.minimal.MavState;
import io.dronefleet.mavlink.minimal.MavType;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct unit tests of {@link DefaultLinkHealth}'s drop-rate accounting. The MAVLink spec defines
 * no drop-rate formula (plan §2.1); these tests pin <i>this class's own</i> documented rule:
 * {@code lost = sum(gap - 1)} over consecutive wrapped {@code seq} gaps, {@code dropRate =
 * lost / (received + lost)}.
 */
class DefaultLinkHealthTest {

    private static final LinkId LINK = new LinkId("test-link");
    private static final PeerId PEER = new PeerId(new SysId(3), new CompId(1));

    @Test
    void noLossRunReportsExactlyZero() {
        DefaultLinkHealth health = new DefaultLinkHealth(Duration.ofSeconds(5));

        for (int seq = 0; seq < 10; seq++) {
            health.recordFrame(frame(seq));
        }

        LinkHealth.Health result = health.of(PEER);
        assertEquals(10, result.received());
        assertEquals(0, result.lost());
        assertEquals(0.0, result.dropRate());
        assertTrue(result.connected());
    }

    @Test
    void gapsAreCountedAsLostFrames() {
        DefaultLinkHealth health = new DefaultLinkHealth(Duration.ofSeconds(5));

        // seq stream: 10, 11, 14 (2 lost), 15, 20 (4 lost) -- 5 received, 6 lost, expected 11 total
        int[] seqs = {10, 11, 14, 15, 20};
        for (int seq : seqs) {
            health.recordFrame(frame(seq));
        }

        LinkHealth.Health result = health.of(PEER);
        assertEquals(5, result.received());
        assertEquals(6, result.lost());
        assertEquals(6.0 / 11.0, result.dropRate(), 1e-9);
    }

    @Test
    void dropRateAccountingHandlesTheEightBitSeqWrap() {
        DefaultLinkHealth health = new DefaultLinkHealth(Duration.ofSeconds(5));

        // 253, 254, 255, 0 (wraps, gap 1, no loss), 3 (gap 3, 2 lost)
        int[] seqs = {253, 254, 255, 0, 3};
        for (int seq : seqs) {
            health.recordFrame(frame(seq));
        }

        LinkHealth.Health result = health.of(PEER);
        assertEquals(5, result.received());
        assertEquals(2, result.lost());
        assertEquals(2.0 / 7.0, result.dropRate(), 1e-9);
    }

    @Test
    void aRepeatedSeqIsCountedAsReceivedButAddsNoLoss() {
        DefaultLinkHealth health = new DefaultLinkHealth(Duration.ofSeconds(5));

        health.recordFrame(frame(5));
        health.recordFrame(frame(5)); // duplicate -- gap 0

        LinkHealth.Health result = health.of(PEER);
        assertEquals(2, result.received());
        assertEquals(0, result.lost());
    }

    @Test
    void anUnheardPeerReportsDisconnectedWithZeroedCounters() {
        DefaultLinkHealth health = new DefaultLinkHealth(Duration.ofSeconds(5));

        LinkHealth.Health result = health.of(new PeerId(new SysId(200), new CompId(1)));

        assertFalse(result.connected());
        assertEquals(0, result.received());
        assertEquals(0, result.lost());
        assertEquals(0.0, result.dropRate());
    }

    @Test
    void movingToADifferentLinkStartsAFreshAccountingWindow() {
        DefaultLinkHealth health = new DefaultLinkHealth(Duration.ofSeconds(5));
        health.recordFrame(frame(LINK, 100));
        health.recordFrame(frame(LINK, 250)); // huge gap on the ORIGINAL link -- would be heavy loss

        LinkId otherLink = new LinkId("other-link");
        health.recordFrame(frame(otherLink, 0)); // fresh window: first sighting on this link

        LinkHealth.Health result = health.of(PEER);
        // the fresh window's first frame contributes 1 received, 0 lost, regardless of the old
        // link's running gap -- proving the two links' counters do not bleed into each other.
        assertEquals(1, result.received());
        assertEquals(0, result.lost());
    }

    private static MavFrame frame(int seq) {
        return frame(LINK, seq);
    }

    private static MavFrame frame(LinkId link, int seq) {
        MavHeader header = new MavHeader(2, seq, PEER.system(), PEER.component(), 0, 0, 0, false);
        Heartbeat heartbeat = Heartbeat.builder()
                .type(MavType.MAV_TYPE_GENERIC)
                .autopilot(MavAutopilot.MAV_AUTOPILOT_GENERIC)
                .baseMode(MavModeFlag.MAV_MODE_FLAG_MANUAL_INPUT_ENABLED)
                .customMode(0)
                .systemStatus(MavState.MAV_STATE_STANDBY)
                .mavlinkVersion(3)
                .build();
        return new MavFrame(header, heartbeat, link, LinkPeer.NONE, Instant.now());
    }
}

package com.drones.mavlink.session;

import com.drones.mavlink.CompId;
import com.drones.mavlink.PeerId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.codec.FrameWriter;
import com.drones.mavlink.config.MavlinkCoreSettings;
import com.drones.mavlink.transport.LinkId;
import com.drones.mavlink.transport.UdpListenLink;
import com.drones.mavlink.transport.UdpTargetLink;

import io.dronefleet.mavlink.minimal.Heartbeat;
import io.dronefleet.mavlink.minimal.MavAutopilot;
import io.dronefleet.mavlink.minimal.MavModeFlag;
import io.dronefleet.mavlink.minimal.MavState;
import io.dronefleet.mavlink.minimal.MavType;
import io.dronefleet.mavlink.util.EnumValue;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wave's headline acceptance gate (docs/plans/active/MAVLINK-CORE-PLAN.md W2 exit criteria):
 * two distinct sysids transmitting {@code HEARTBEAT}s into <b>one</b> {@link UdpListenLink}, driven
 * through a real {@link MavlinkSession} (real sockets, real per-link reader thread, real
 * {@link com.drones.mavlink.codec.FrameReader} resync). Proves {@link PeerDirectory} holds both
 * with correct {@link HeartbeatInfo}, and that neither vehicle's frames leak into the other's
 * {@link LinkHealth} accounting -- the exact defect this wave's per-{@code (PeerId, LinkId)}
 * accounting exists to avoid.
 */
class MultiPeerLoopbackTest {

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(3);

    @Test
    void twoSysidsOnOneListenLinkAreTrackedAndAccountedForIndependently() throws Exception {
        try (UdpListenLink listenLink = new UdpListenLink("127.0.0.1", 0)) {
            MavlinkSession session = new MavlinkSession(MavlinkNode.groundStation(), MavlinkCoreSettings.defaults());
            try {
                session.addLink(listenLink);
                int port = extractPort(listenLink.id());

                try (UdpTargetLink vehicleOneLink = new UdpTargetLink("127.0.0.1", port);
                     UdpTargetLink vehicleTwoLink = new UdpTargetLink("127.0.0.1", port)) {

                    PeerId peerOne = new PeerId(new SysId(11), new CompId(1));
                    PeerId peerTwo = new PeerId(new SysId(22), new CompId(1));

                    FrameWriter vehicleOneWriter = new FrameWriter(peerOne.system(), peerOne.component());
                    vehicleOneWriter.addLink(vehicleOneLink);
                    FrameWriter vehicleTwoWriter = new FrameWriter(peerTwo.system(), peerTwo.component());
                    vehicleTwoWriter.addLink(vehicleTwoLink);

                    // vehicle 1: three consecutive heartbeats -- no loss expected
                    for (int i = 0; i < 3; i++) {
                        vehicleOneWriter.broadcast(heartbeat(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA), vehicleOneLink.id());
                    }
                    // vehicle 2: two consecutive heartbeats -- no loss expected
                    for (int i = 0; i < 2; i++) {
                        vehicleTwoWriter.broadcast(heartbeat(MavAutopilot.MAV_AUTOPILOT_PX4), vehicleTwoLink.id());
                    }

                    awaitPeerCount(session, 2, POLL_TIMEOUT);

                    Peer p1 = session.peers().peer(peerOne);
                    Peer p2 = session.peers().peer(peerTwo);
                    assertNotNull(p1, "vehicle 1 must be known to PeerDirectory");
                    assertNotNull(p2, "vehicle 2 must be known to PeerDirectory");

                    assertNotNull(p1.heartbeat(), "vehicle 1's HeartbeatInfo must be captured");
                    assertNotNull(p2.heartbeat(), "vehicle 2's HeartbeatInfo must be captured");
                    assertEquals(EnumValue.of(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA).value(), p1.heartbeat().autopilot());
                    assertEquals(EnumValue.of(MavAutopilot.MAV_AUTOPILOT_PX4).value(), p2.heartbeat().autopilot());

                    assertEquals(listenLink.id(), p1.link());
                    assertEquals(listenLink.id(), p2.link());

                    LinkHealth.Health h1 = pollLinkHealth(session, peerOne, 3, POLL_TIMEOUT);
                    LinkHealth.Health h2 = pollLinkHealth(session, peerTwo, 2, POLL_TIMEOUT);

                    assertEquals(3, h1.received(), "vehicle 1's LinkHealth must count exactly its own 3 heartbeats");
                    assertEquals(0, h1.lost(), "vehicle 1 sent consecutively -- no loss");
                    assertEquals(2, h2.received(), "vehicle 2's LinkHealth must count exactly its own 2 heartbeats, "
                            + "not leaking vehicle 1's frames onto its counters");
                    assertEquals(0, h2.lost(), "vehicle 2 sent consecutively -- no loss");
                }
            } finally {
                session.close();
            }
        }
    }

    private static Heartbeat heartbeat(MavAutopilot autopilot) {
        return Heartbeat.builder()
                .type(MavType.MAV_TYPE_QUADROTOR)
                .autopilot(autopilot)
                .baseMode(MavModeFlag.MAV_MODE_FLAG_MANUAL_INPUT_ENABLED)
                .customMode(0)
                .systemStatus(MavState.MAV_STATE_STANDBY)
                .mavlinkVersion(3)
                .build();
    }

    private static void awaitPeerCount(MavlinkSession session, int expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (session.peers().peers().size() >= expected) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(session.peers().peers().size() >= expected,
                "expected " + expected + " peers within " + timeout + ", got " + session.peers().peers().size());
    }

    private static LinkHealth.Health pollLinkHealth(MavlinkSession session, PeerId peer, long expectedReceived,
                                                      Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        LinkHealth.Health last = session.health().of(peer);
        while (System.nanoTime() < deadline) {
            last = session.health().of(peer);
            if (last.received() >= expectedReceived) {
                return last;
            }
            Thread.sleep(10);
        }
        return last;
    }

    /** {@code LinkId} value is {@code "udp-listen:<host>:<port>"} -- pull the bound port back out of it. */
    private static int extractPort(LinkId id) {
        String value = id.value();
        return Integer.parseInt(value.substring(value.lastIndexOf(':') + 1));
    }
}

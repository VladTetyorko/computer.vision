package com.drones.vision.adapter.mavlink;

import com.drones.mavlink.CompId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.codec.FrameWriter;
import com.drones.mavlink.transport.UdpTargetLink;

import io.dronefleet.mavlink.minimal.Heartbeat;
import io.dronefleet.mavlink.minimal.MavAutopilot;
import io.dronefleet.mavlink.minimal.MavModeFlag;
import io.dronefleet.mavlink.minimal.MavState;
import io.dronefleet.mavlink.minimal.MavType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MavlinkTelemetrySource#intakeStatus(int)} / {@link MavlinkGateway#intakeStatus(String)}
 * (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md A2) — the P1/P2 diagnostic the two counters exist
 * for. Real-loopback throughout, same style as {@link MavlinkLobbyHoldTest}: a raw {@link
 * DatagramSocket} stands in for "something wrong is talking to this port" (P2's garbage case), and
 * {@link UdpTargetLink}/{@link FrameWriter} stand in for a real vehicle (proving {@code
 * framesDecoded} genuinely advances, not just {@code datagramsReceived}).
 */
class MavlinkIntakeStatusTest {

    @Test
    void unboundPortReportsTheHonestUnboundStatus() throws Exception {
        MavlinkTelemetrySource source = new MavlinkTelemetrySource();
        int port = freePort();

        MavlinkIntakeStatus status = source.intakeStatus(port);

        assertEquals(MavlinkIntakeStatus.unbound(MavlinkTelemetrySource.DEFAULT_BIND_HOST + ":" + port), status);
    }

    @Test
    void rejectsAnOutOfRangePort() {
        MavlinkTelemetrySource source = new MavlinkTelemetrySource();

        assertThrows(IllegalArgumentException.class, () -> source.intakeStatus(0));
        assertThrows(IllegalArgumentException.class, () -> source.intakeStatus(65_536));
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void aNonMavlinkDatagramIncrementsDatagramsReceivedButNeverFramesDecoded() throws Exception {
        MavlinkTelemetrySource source = new MavlinkTelemetrySource();
        int port = freePort();

        try {
            source.holdLobby(port);

            byte[] garbage = "not a mavlink frame".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (DatagramSocket socket = new DatagramSocket()) {
                DatagramPacket packet = new DatagramPacket(garbage, garbage.length, InetAddress.getByName("127.0.0.1"), port);
                socket.send(packet);
            }

            MavlinkIntakeStatus status = awaitDatagrams(source, port, 1, Duration.ofSeconds(10));

            assertTrue(status.bound());
            assertTrue(status.datagramsReceived() >= 1, "the garbage datagram must still be counted pre-parse");
            assertTrue(status.bytesReceived() >= garbage.length);
            assertEquals(0, status.framesDecoded(), "garbage bytes can never resync as a MAVLink frame");
            assertTrue(status.unclaimedSysids().isEmpty());
            assertTrue(status.claimedSysids().isEmpty());
        } finally {
            source.releaseLobby(port);
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void aRealHeartbeatAdvancesBothDatagramsAndFramesDecoded() throws Exception {
        MavlinkTelemetrySource source = new MavlinkTelemetrySource();
        int port = freePort();
        int sysid = 191;

        try {
            source.holdLobby(port);
            assertTrue(source.intakeStatus(port).lobbyHeld());

            try (UdpTargetLink link = new UdpTargetLink("127.0.0.1", port)) {
                FrameWriter writer = new FrameWriter(new SysId(sysid), new CompId(MavlinkFlightCommander.TARGET_COMPONENT_AUTOPILOT));
                writer.addLink(link);
                writer.broadcast(heartbeat(), link.id());

                MavlinkIntakeStatus status = awaitFramesDecoded(source, port, 1, Duration.ofSeconds(15));

                assertTrue(status.bound());
                assertTrue(status.lobbyHeld());
                assertTrue(status.datagramsReceived() >= 1);
                assertTrue(status.bytesReceived() > 0);
                assertTrue(status.framesDecoded() >= 1, "a real HEARTBEAT must decode");
                assertTrue(status.unclaimedSysids().contains(sysid),
                        "a lobby hold never claims -- the sysid must surface as unclaimed, not claimed");
                assertFalse(status.claimedSysids().contains(sysid));
            }
        } finally {
            source.releaseLobby(port);
        }
    }

    private static Heartbeat heartbeat() {
        return Heartbeat.builder()
                .type(MavType.MAV_TYPE_QUADROTOR)
                .autopilot(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA)
                .baseMode(MavModeFlag.MAV_MODE_FLAG_SAFETY_ARMED, MavModeFlag.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED)
                .customMode(0)
                .systemStatus(MavState.MAV_STATE_ACTIVE)
                .mavlinkVersion(3)
                .build();
    }

    private static int freePort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static MavlinkIntakeStatus awaitDatagrams(
            MavlinkTelemetrySource source, int port, long minDatagrams, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            MavlinkIntakeStatus status = source.intakeStatus(port);
            if (status.datagramsReceived() >= minDatagrams) {
                return status;
            }
            Thread.sleep(150);
        }
        throw new AssertionError("expected >= " + minDatagrams + " datagrams on port " + port + " within " + timeout
                + "; got " + source.intakeStatus(port));
    }

    private static MavlinkIntakeStatus awaitFramesDecoded(
            MavlinkTelemetrySource source, int port, long minFrames, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            MavlinkIntakeStatus status = source.intakeStatus(port);
            if (status.framesDecoded() >= minFrames) {
                return status;
            }
            Thread.sleep(150);
        }
        throw new AssertionError("expected >= " + minFrames + " frames decoded on port " + port + " within " + timeout
                + "; got " + source.intakeStatus(port));
    }
}

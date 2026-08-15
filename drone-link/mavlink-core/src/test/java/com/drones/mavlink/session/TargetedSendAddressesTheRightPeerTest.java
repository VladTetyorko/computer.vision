package com.drones.mavlink.session;

import com.drones.mavlink.CompId;
import com.drones.mavlink.PeerId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.codec.FrameWriter;
import com.drones.mavlink.config.MavlinkCoreSettings;
import com.drones.mavlink.transport.ByteChunk;
import com.drones.mavlink.transport.LinkId;
import com.drones.mavlink.transport.UdpListenLink;
import com.drones.mavlink.transport.UdpTargetLink;

import io.dronefleet.mavlink.common.CommandLong;
import io.dronefleet.mavlink.common.MavCmd;
import io.dronefleet.mavlink.minimal.Heartbeat;
import io.dronefleet.mavlink.minimal.MavAutopilot;
import io.dronefleet.mavlink.minimal.MavModeFlag;
import io.dronefleet.mavlink.minimal.MavState;
import io.dronefleet.mavlink.minimal.MavType;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression proof for the fleet-gateway addressing defect: a <b>targeted</b> send must reach the
 * peer it names, not whichever vehicle transmitted most recently.
 *
 * <p>Two vehicles share one bound port — this platform's normal production shape. Vehicle two speaks
 * last, so the listen link's own {@code defaultTarget()} is vehicle two's address. Sending to
 * vehicle <b>one</b> must still arrive at vehicle one. Before {@code FrameWriter#sendTo} existed,
 * {@code RoutingFrameSink} resolved the correct link but then let {@code defaultTarget()} pick the
 * address, so this command would have been delivered to the wrong aircraft — for an {@code ARM} or a
 * mode change that is not a routing inaccuracy, it is the wrong aircraft.
 */
class TargetedSendAddressesTheRightPeerTest {

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(3);
    private static final PeerId VEHICLE_ONE = new PeerId(new SysId(11), new CompId(1));
    private static final PeerId VEHICLE_TWO = new PeerId(new SysId(22), new CompId(1));

    @Test
    void aTargetedSendReachesTheNamedPeerNotTheMostRecentlyHeardOne() throws Exception {
        try (UdpListenLink gateway = new UdpListenLink("127.0.0.1", 0)) {
            MavlinkSession session = new MavlinkSession(MavlinkNode.groundStation(), MavlinkCoreSettings.defaults());
            try {
                session.addLink(gateway);
                int port = portOf(gateway.id());

                try (UdpTargetLink vehicleOne = new UdpTargetLink("127.0.0.1", port);
                     UdpTargetLink vehicleTwo = new UdpTargetLink("127.0.0.1", port)) {

                    heartbeatFrom(VEHICLE_ONE, vehicleOne);
                    awaitPeer(session, VEHICLE_ONE);

                    // Vehicle two speaks *after* vehicle one, making its address the gateway link's
                    // defaultTarget() — the exact condition that used to misdeliver.
                    heartbeatFrom(VEHICLE_TWO, vehicleTwo);
                    awaitPeer(session, VEHICLE_TWO);

                    session.sink().send(armCommand(), VEHICLE_ONE);

                    ByteChunk atVehicleOne = vehicleOne.poll(POLL_TIMEOUT);
                    assertNotNull(atVehicleOne, "the command must arrive at the vehicle it was addressed to");
                    assertTrue(atVehicleOne.length() > 0, "a command datagram must carry bytes");

                    assertNull(vehicleTwo.poll(Duration.ofMillis(300)),
                            "the command must NOT reach vehicle two, which merely spoke most recently");
                }
            } finally {
                session.close();
            }
        }
    }

    private static void heartbeatFrom(PeerId vehicle, UdpTargetLink link) {
        FrameWriter writer = new FrameWriter(vehicle.system(), vehicle.component());
        writer.addLink(link);
        writer.broadcast(Heartbeat.builder()
                .type(MavType.MAV_TYPE_QUADROTOR)
                .autopilot(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA)
                .baseMode(MavModeFlag.MAV_MODE_FLAG_MANUAL_INPUT_ENABLED)
                .customMode(0)
                .systemStatus(MavState.MAV_STATE_STANDBY)
                .mavlinkVersion(3)
                .build(), link.id());
    }

    private static CommandLong armCommand() {
        return CommandLong.builder()
                .targetSystem(VEHICLE_ONE.system().value())
                .targetComponent(VEHICLE_ONE.component().value())
                .command(MavCmd.MAV_CMD_COMPONENT_ARM_DISARM)
                .param1(1.0f)
                .build();
    }

    private static void awaitPeer(MavlinkSession session, PeerId peer) throws InterruptedException {
        long deadline = System.nanoTime() + POLL_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (session.peers().peer(peer) != null) {
                return;
            }
            Thread.sleep(10);
        }
        assertNotNull(session.peers().peer(peer), "peer " + peer + " was never learned within " + POLL_TIMEOUT);
    }

    /** {@code LinkId} value is {@code "udp-listen:<host>:<port>"} — pull the bound port back out of it. */
    private static int portOf(LinkId id) {
        String value = id.value();
        return Integer.parseInt(value.substring(value.lastIndexOf(':') + 1));
    }
}

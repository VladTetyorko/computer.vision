package com.drones.vision.adapter.mavlink;

import com.drones.mavlink.CompId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.codec.FrameReader;
import com.drones.mavlink.codec.FrameWriter;
import com.drones.mavlink.transport.ByteChunk;
import com.drones.mavlink.transport.UdpTargetLink;

import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.warehouse.domain.model.Device;

import io.dronefleet.mavlink.common.CommandAck;
import io.dronefleet.mavlink.common.CommandLong;
import io.dronefleet.mavlink.common.MavCmd;
import io.dronefleet.mavlink.common.MavResult;
import io.dronefleet.mavlink.minimal.Heartbeat;
import io.dronefleet.mavlink.minimal.MavAutopilot;
import io.dronefleet.mavlink.minimal.MavModeFlag;
import io.dronefleet.mavlink.minimal.MavState;
import io.dronefleet.mavlink.minimal.MavType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The required real-loopback proof of wave O8's Mechanism A (docs/plans/active/
 * DRONE-ONBOARDING-PLAN.md): a genuine {@link FakeVehicle} over real UDP loopback, one real bound
 * {@link MavlinkGateway} socket via {@link MavlinkTelemetrySource} -- the same pattern {@link
 * MavlinkFlightCommanderTest}/{@link MavlinkMessageInventoryIntegrationTest} already use for their
 * own required real-loopback proofs. {@link MavlinkConnectRemediatorTest} covers the finer-grained
 * sequencing/idempotency arithmetic with fast hand-fakes; this class proves that arithmetic holds,
 * and — critically — that the flag off means <b>zero</b> commands, over the real socket path a
 * physical vehicle would actually use. {@link MavlinkSitlOnConnectIntegrationTest} is the further,
 * docker-gated proof against real ArduPilot firmware.
 */
class MavlinkConnectRemediationIntegrationTest {

    private static final int TARGET_COMPONENT_AUTOPILOT = MavlinkFlightCommander.TARGET_COMPONENT_AUTOPILOT;

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void onConnectTheConfiguredMessageSetArrivesInOrderAndIsNotResentOnLaterHeartbeats() throws Exception {
        int port = freePort();
        int sysid = 61;
        List<MavlinkSettings.Onboarding.MessageRequest> requests = List.of(
                new MavlinkSettings.Onboarding.MessageRequest(1, Duration.ofMillis(300)),   // SYS_STATUS
                new MavlinkSettings.Onboarding.MessageRequest(33, Duration.ofMillis(300)),  // GLOBAL_POSITION_INT
                new MavlinkSettings.Onboarding.MessageRequest(74, Duration.ofMillis(300))); // VFR_HUD
        MavlinkTelemetrySource source = new MavlinkTelemetrySource(settingsWithOnConnectRequests(requests));
        DeviceId deviceId = DeviceId.random();
        FakeVehicle vehicle = null;

        try {
            source.open(device(port, deviceId, sysid));
            vehicle = FakeVehicle.start(port, sysid);

            for (MavlinkSettings.Onboarding.MessageRequest expected : requests) {
                CommandLong sent = vehicle.awaitCommandLong(Duration.ofSeconds(5));
                assertEquals(MavCmd.MAV_CMD_SET_MESSAGE_INTERVAL, sent.command().entry());
                assertEquals(sysid, sent.targetSystem());
                assertEquals(TARGET_COMPONENT_AUTOPILOT, sent.targetComponent());
                assertEquals((float) expected.messageId(), sent.param1());
                float expectedMicros = expected.interval().toNanos() / (float) TimeUnit.MICROSECONDS.toNanos(1);
                assertEquals(expectedMicros, sent.param2());
                vehicle.replyAck(MavCmd.MAV_CMD_SET_MESSAGE_INTERVAL, MavResult.MAV_RESULT_ACCEPTED);
            }

            // The peer keeps heartbeating every 200ms (well inside the 30s default silence window) --
            // none of that must trigger a second round. This is the real-UDP proof that
            // MavlinkConnectRemediator's per-peer bookkeeping (unit-tested in isolation by
            // MavlinkConnectRemediatorTest) actually holds over the socket path a real vehicle uses.
            FakeVehicle stillLive = vehicle;
            assertThrows(AssertionError.class, () -> stillLive.awaitCommandLong(Duration.ofSeconds(1)),
                    "a peer heard continuously must not be re-remediated on every heartbeat");
        } finally {
            if (vehicle != null) {
                vehicle.close();
            }
            source.close(deviceId);
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void withTheFlagOffNotASingleCommandIsEverSent() throws Exception {
        int port = freePort();
        int sysid = 62;
        // MavlinkSettings.defaults() -- requestMessagesOnConnect() is false, same as production.
        MavlinkTelemetrySource source = new MavlinkTelemetrySource(MavlinkSettings.defaults());
        DeviceId deviceId = DeviceId.random();
        FakeVehicle vehicle = null;

        try {
            source.open(device(port, deviceId, sysid));
            vehicle = FakeVehicle.start(port, sysid);

            // Several heartbeats' worth of waiting -- long enough that if MavlinkConnectRemediator
            // were wrongly constructed despite the flag, its first request would have arrived by now.
            FakeVehicle stillLive = vehicle;
            assertThrows(AssertionError.class, () -> stillLive.awaitCommandLong(Duration.ofSeconds(3)),
                    "flag off must mean literally zero commands sent, not merely fewer");
        } finally {
            if (vehicle != null) {
                vehicle.close();
            }
            source.close(deviceId);
        }
    }

    private static MavlinkSettings settingsWithOnConnectRequests(List<MavlinkSettings.Onboarding.MessageRequest> requests) {
        MavlinkSettings.Onboarding onboarding = new MavlinkSettings.Onboarding(
                List.of(), Duration.ofSeconds(1), 1, Duration.ofSeconds(1), 1, true, requests);
        return MavlinkSettings.defaults().withOnboarding(onboarding);
    }

    private static Device device(int port, DeviceId id, int sysid) {
        return new Device(id, "connect-remediation-test-device", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("mavlink", URI.create("udp://127.0.0.1:" + port),
                        Map.of("sysid", String.valueOf(sysid))));
    }

    private static int freePort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * A minimal test double speaking just enough MAVLink to stand in for a real vehicle: heartbeats
     * (so {@link MavlinkGateway} claims and labels it, exactly like a real aircraft) plus the ability
     * to observe {@code COMMAND_LONG}s the gateway sends and answer them. Deliberately reimplemented
     * here rather than shared with {@link MavlinkFlightCommanderTest}/{@code
     * MavlinkManualControlSenderTest}'s own private copies -- this module's existing convention for
     * this exact helper, not an oversight.
     */
    private static final class FakeVehicle implements AutoCloseable {
        private static final long HEARTBEAT_PERIOD_MILLIS = 200L;

        private final UdpTargetLink link;
        private final FrameWriter writer;
        private final FrameReader reader;
        private final AtomicBoolean stopped = new AtomicBoolean(false);
        private final Thread heartbeatThread;

        private FakeVehicle(UdpTargetLink link, FrameWriter writer, int sysid) {
            this.link = link;
            this.writer = writer;
            this.reader = new FrameReader(link);
            this.heartbeatThread = new Thread(this::heartbeatLoop, "fake-vehicle-heartbeat-" + sysid);
            this.heartbeatThread.setDaemon(true);
        }

        static FakeVehicle start(int gatewayPort, int sysid) throws IOException {
            UdpTargetLink link = new UdpTargetLink("127.0.0.1", gatewayPort);
            FrameWriter writer = new FrameWriter(new SysId(sysid), new CompId(TARGET_COMPONENT_AUTOPILOT));
            writer.addLink(link);
            FakeVehicle vehicle = new FakeVehicle(link, writer, sysid);
            vehicle.heartbeatThread.start();
            return vehicle;
        }

        private void heartbeatLoop() {
            while (!stopped.get()) {
                try {
                    sendHeartbeat();
                    Thread.sleep(HEARTBEAT_PERIOD_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException e) {
                    return; // link closing (or closed) -- stop quietly, see close()
                }
            }
        }

        private void sendHeartbeat() {
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

        /** Blocks (bounded by {@code timeout}) until a {@code COMMAND_LONG} arrives, skipping anything else. */
        CommandLong awaitCommandLong(Duration timeout) throws IOException {
            long deadlineNanos = System.nanoTime() + timeout.toNanos();
            AtomicReference<CommandLong> found = new AtomicReference<>();
            while (found.get() == null) {
                long remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                if (remainingMillis <= 0) {
                    break;
                }
                ByteChunk chunk = link.poll(Duration.ofMillis(remainingMillis));
                if (chunk == null) {
                    continue;
                }
                reader.offer(chunk, frame -> {
                    if (frame.is(CommandLong.class)) {
                        found.compareAndSet(null, frame.as(CommandLong.class));
                    }
                });
            }
            if (found.get() == null) {
                throw new AssertionError("expected a COMMAND_LONG within " + timeout);
            }
            return found.get();
        }

        /** Acknowledges a specific command. */
        void replyAck(MavCmd command, MavResult result) {
            CommandAck ack = CommandAck.builder()
                    .command(command)
                    .result(result)
                    .build();
            writer.broadcast(ack, link.id());
        }

        @Override
        public void close() {
            stopped.set(true);
            link.close();
            try {
                heartbeatThread.join(Duration.ofSeconds(2).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

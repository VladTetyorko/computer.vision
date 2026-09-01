package com.drones.vision.adapter.mavlink;

import com.drones.mavlink.CompId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.codec.FrameReader;
import com.drones.mavlink.codec.FrameWriter;
import com.drones.mavlink.session.CorrelationKeys;
import com.drones.mavlink.transport.ByteChunk;
import com.drones.mavlink.transport.UdpTargetLink;

import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.warehouse.domain.model.Device;

import io.dronefleet.mavlink.common.AutopilotVersion;
import io.dronefleet.mavlink.common.CommandAck;
import io.dronefleet.mavlink.common.CommandLong;
import io.dronefleet.mavlink.common.MavCmd;
import io.dronefleet.mavlink.common.MavResult;
import io.dronefleet.mavlink.minimal.Heartbeat;
import io.dronefleet.mavlink.minimal.MavAutopilot;
import io.dronefleet.mavlink.minimal.MavModeFlag;
import io.dronefleet.mavlink.minimal.MavState;
import io.dronefleet.mavlink.minimal.MavType;
import io.dronefleet.mavlink.util.EnumValue;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The required real-loopback proof of wave P2 (docs/plans/active/MAVLINK-COMMANDS-PLAN.md D2c): a
 * genuine {@link FakeVehicle} over real UDP loopback, one real bound {@link MavlinkGateway} socket
 * via {@link MavlinkTelemetrySource} — the same pattern {@link
 * MavlinkConnectRemediationIntegrationTest}/{@link MavlinkFlightCommanderTest} already use for their
 * own required real-loopback proofs.
 *
 * <p>{@link MavlinkStreamNegotiator#negotiate} fires two exchanges concurrently (the {@code
 * AUTOPILOT_VERSION} probe and the {@code SET_MESSAGE_INTERVAL} chain — see that class's own
 * javadoc), so which one's first {@code COMMAND_LONG} lands on the wire first is not deterministic.
 * {@code FakeVehicle#collectNegotiation} accounts for that: it only asserts strict ordering
 * <i>within</i> the interval chain (guaranteed by {@link
 * java.util.concurrent.CompletableFuture#thenCompose} chaining), not between the chain and the
 * independent probe.
 */
class MavlinkStreamNegotiatorTest {

    private static final int TARGET_COMPONENT_AUTOPILOT = MavlinkFlightCommander.TARGET_COMPONENT_AUTOPILOT;

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void negotiationFiresOnClaimWithCorrectIdsAndConfiguredRates() throws Exception {
        int port = freePort();
        int sysid = 71;
        List<MavlinkSettings.Onboarding.MessageRequest> streams = List.of(
                new MavlinkSettings.Onboarding.MessageRequest(30, Duration.ofMillis(250)),  // ATTITUDE
                new MavlinkSettings.Onboarding.MessageRequest(33, Duration.ofMillis(250))); // GLOBAL_POSITION_INT
        MavlinkTelemetrySource source = new MavlinkTelemetrySource(settingsWithStreams(streams));
        DeviceId deviceId = DeviceId.random();
        FakeVehicle vehicle = null;

        try {
            source.open(device(port, deviceId, sysid));
            vehicle = FakeVehicle.start(port, sysid);

            NegotiationOutcome outcome = vehicle.collectNegotiation(streams.size(), MavResult.MAV_RESULT_ACCEPTED);

            assertTrue(outcome.probeSeen(), "expected one MAV_CMD_REQUEST_MESSAGE(AUTOPILOT_VERSION) probe on claim");
            assertEquals(streams.size(), outcome.intervalCommands().size());
            for (int i = 0; i < streams.size(); i++) {
                MavlinkSettings.Onboarding.MessageRequest expected = streams.get(i);
                CommandLong sent = outcome.intervalCommands().get(i);
                assertEquals(MavCmd.MAV_CMD_SET_MESSAGE_INTERVAL, sent.command().entry());
                assertEquals(sysid, sent.targetSystem());
                assertEquals(TARGET_COMPONENT_AUTOPILOT, sent.targetComponent());
                assertEquals((float) expected.messageId(), sent.param1());
                float expectedMicros = expected.interval().toNanos() / (float) TimeUnit.MICROSECONDS.toNanos(1);
                assertEquals(expectedMicros, sent.param2());
            }
        } finally {
            if (vehicle != null) {
                vehicle.close();
            }
            source.close(deviceId);
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void unsupportedIntervalAckDoesNotBreakTheClaimOrStopTheChain() throws Exception {
        int port = freePort();
        int sysid = 72;
        List<MavlinkSettings.Onboarding.MessageRequest> streams = List.of(
                new MavlinkSettings.Onboarding.MessageRequest(30, Duration.ofMillis(250)),
                new MavlinkSettings.Onboarding.MessageRequest(65, Duration.ofMillis(250)));
        MavlinkTelemetrySource source = new MavlinkTelemetrySource(settingsWithStreams(streams));
        DeviceId deviceId = DeviceId.random();
        FakeVehicle vehicle = null;

        Device deviceDescriptor = device(port, deviceId, sysid);
        try {
            source.open(deviceDescriptor);
            vehicle = FakeVehicle.start(port, sysid);

            // Every SET_MESSAGE_INTERVAL answered UNSUPPORTED -- a vehicle that doesn't implement
            // negotiation. The chain must still proceed through every configured message (never
            // truncate on the first refusal), and the claim itself must remain healthy throughout:
            // telemetry frames continue to be decoded and published for the claimed device.
            NegotiationOutcome outcome = vehicle.collectNegotiation(streams.size(), MavResult.MAV_RESULT_UNSUPPORTED);
            assertEquals(streams.size(), outcome.intervalCommands().size(),
                    "an UNSUPPORTED ack must not truncate the remaining requests in the chain");

            // The claim itself survived: the gateway still reports this sysid as claimed by our device.
            String bindKey = source.bindKeyFor(deviceDescriptor);
            MavlinkGateway.CommandTarget target = null;
            for (int attempt = 0; attempt < 50 && target == null; attempt++) {
                target = source.commandTarget(bindKey, deviceId);
                if (target == null) {
                    Thread.sleep(20);
                }
            }
            assertNotNull(target, "claim must survive an UNSUPPORTED negotiation ack");
            assertEquals(sysid, target.sysid());
        } finally {
            if (vehicle != null) {
                vehicle.close();
            }
            source.close(deviceId);
        }
    }

    @Test
    void constructorRejectsNullCollaborators() {
        assertThrows(NullPointerException.class,
                () -> new MavlinkStreamNegotiator(null, null, MavlinkSettings.defaults()));
    }

    private static MavlinkSettings settingsWithStreams(List<MavlinkSettings.Onboarding.MessageRequest> streams) {
        return MavlinkSettings.defaults()
                .withStreamNegotiation(new MavlinkSettings.StreamNegotiation(streams))
                .withCommandRetries(1)
                .withAckTimeout(Duration.ofMillis(500));
    }

    private static Device device(int port, DeviceId id, int sysid) {
        return new Device(id, "stream-negotiator-test-device", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("mavlink", URI.create("udp://127.0.0.1:" + port),
                        Map.of("sysid", String.valueOf(sysid))));
    }

    private static int freePort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** One negotiation's observed shape: whether the capability probe arrived, and the interval commands in send order. */
    private record NegotiationOutcome(boolean probeSeen, List<CommandLong> intervalCommands) {
    }

    /**
     * A minimal test double speaking just enough MAVLink to stand in for a real vehicle: heartbeats
     * (so {@link MavlinkGateway} claims and labels it, exactly like a real aircraft) plus the ability
     * to observe {@code COMMAND_LONG}s the gateway/negotiator sends and answer them. Deliberately
     * reimplemented here rather than shared with this module's other test files' own private copies —
     * this module's existing convention for this exact helper (see {@link
     * MavlinkConnectRemediationIntegrationTest}'s own javadoc), not an oversight.
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

        /**
         * Observes negotiation's whole shape for one claim: replies to the one {@code
         * MAV_CMD_REQUEST_MESSAGE(AUTOPILOT_VERSION)} probe with a real {@code AUTOPILOT_VERSION}
         * reply, and to each of {@code expectedIntervalCommands} {@code MAV_CMD_SET_MESSAGE_INTERVAL}
         * requests with {@code intervalResult} — proceeding to the next only after replying, exactly
         * matching {@link MavlinkStreamNegotiator}'s own chained send order.
         */
        NegotiationOutcome collectNegotiation(int expectedIntervalCommands, MavResult intervalResult) throws IOException {
            boolean probeSeen = false;
            List<CommandLong> intervalCommands = new java.util.ArrayList<>();
            while (intervalCommands.size() < expectedIntervalCommands) {
                CommandLong sent = awaitCommandLong(Duration.ofSeconds(5));
                if (sent.command().entry() == MavCmd.MAV_CMD_REQUEST_MESSAGE) {
                    assertFalse(probeSeen, "expected exactly one AUTOPILOT_VERSION probe per claim");
                    assertEquals((float) CorrelationKeys.AUTOPILOT_VERSION_MESSAGE_ID, sent.param1());
                    probeSeen = true;
                    replyAutopilotVersion();
                } else if (sent.command().entry() == MavCmd.MAV_CMD_SET_MESSAGE_INTERVAL) {
                    intervalCommands.add(sent);
                    replyAck(MavCmd.MAV_CMD_SET_MESSAGE_INTERVAL, intervalResult);
                } else {
                    throw new AssertionError("unexpected command during negotiation: " + sent.command().entry());
                }
            }
            // The probe races the interval chain independently -- give it a little more time to
            // arrive if the chain happened to finish first.
            if (!probeSeen) {
                CommandLong sent = awaitCommandLong(Duration.ofSeconds(5));
                assertEquals(MavCmd.MAV_CMD_REQUEST_MESSAGE, sent.command().entry());
                assertEquals((float) CorrelationKeys.AUTOPILOT_VERSION_MESSAGE_ID, sent.param1());
                probeSeen = true;
                replyAutopilotVersion();
            }
            return new NegotiationOutcome(probeSeen, List.copyOf(intervalCommands));
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

        /** Answers {@code MAV_CMD_REQUEST_MESSAGE(AUTOPILOT_VERSION)} the way {@link
         * com.drones.mavlink.service.CapabilityService} expects: the message itself, not an ack. */
        void replyAutopilotVersion() {
            AutopilotVersion version = AutopilotVersion.builder()
                    .capabilities(EnumValue.create(0))
                    .flightSwVersion(0)
                    .boardVersion(0)
                    .vendorId(0)
                    .productId(0)
                    .build();
            writer.broadcast(version, link.id());
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

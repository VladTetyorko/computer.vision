package com.drones.mavlink.service;

import com.drones.mavlink.PeerId;
import com.drones.mavlink.config.MavlinkCoreSettings;
import com.drones.mavlink.session.MavlinkNode;
import com.drones.mavlink.session.MavlinkSession;
import com.drones.mavlink.transport.UdpListenLink;

import io.dronefleet.mavlink.common.CommandLong;
import io.dronefleet.mavlink.common.MavCmd;
import io.dronefleet.mavlink.common.MavResult;
import io.dronefleet.mavlink.minimal.MavAutopilot;
import io.dronefleet.mavlink.minimal.MavType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link MessageIntervalService} over a real loopback {@link MavlinkSession} + {@link FakeVehicle} --
 * this class is a thin wrapper over {@link CommandService} (already proven independently by
 * {@code CommandServiceTest}), so these tests focus on the one thing worth getting wrong: the
 * microsecond unit conversion on {@code MAV_CMD_SET_MESSAGE_INTERVAL}'s {@code param2}.
 */
class MessageIntervalServiceTest {

    private static final Duration TIMEOUT = Duration.ofMillis(300);
    private static final int RETRIES = 1;

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void setMessageIntervalSendsParam2InMicroseconds() throws Exception {
        try (Harness h = Harness.start(211)) {
            CompletableFuture<CommandService.CommandOutcome> future =
                    h.messageIntervalService.setMessageInterval(h.target, 32, Duration.ofSeconds(1));

            CommandLong received = h.vehicle.awaitFrame(CommandLong.class, Duration.ofSeconds(5));
            assertEquals(MavCmd.MAV_CMD_SET_MESSAGE_INTERVAL, received.command().entry());
            assertEquals(32f, received.param1(), "param1 must be the message id");
            assertEquals(1_000_000f, received.param2(), "1 Hz (a 1s interval) must be sent as 1,000,000 microseconds");
            h.vehicle.replyAck(MavCmd.MAV_CMD_SET_MESSAGE_INTERVAL, MavResult.MAV_RESULT_ACCEPTED);

            CommandService.CommandOutcome outcome = future.get(10, TimeUnit.SECONDS);
            assertEquals(CommandService.CommandOutcome.Status.ACCEPTED, outcome.status());
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void aFasterRateStillConvertsExactlyToMicroseconds() throws Exception {
        try (Harness h = Harness.start(212)) {
            // 10 Hz -> 100ms period -> 100,000 microseconds.
            h.messageIntervalService.setMessageInterval(h.target, 33, Duration.ofMillis(100));

            CommandLong received = h.vehicle.awaitFrame(CommandLong.class, Duration.ofSeconds(5));
            assertEquals(100_000f, received.param2());
            h.vehicle.replyAck(MavCmd.MAV_CMD_SET_MESSAGE_INTERVAL, MavResult.MAV_RESULT_ACCEPTED);
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void disableMessageSendsTheSpecsOwnDisableSentinel() throws Exception {
        try (Harness h = Harness.start(213)) {
            h.messageIntervalService.disableMessage(h.target, 34);

            CommandLong received = h.vehicle.awaitFrame(CommandLong.class, Duration.ofSeconds(5));
            assertEquals(34f, received.param1());
            assertEquals(-1f, received.param2(), "param2 = -1 is the spec's own 'disable this stream' sentinel");
            h.vehicle.replyAck(MavCmd.MAV_CMD_SET_MESSAGE_INTERVAL, MavResult.MAV_RESULT_ACCEPTED);
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void requestMessageSendsTheMessageIdAsParam1() throws Exception {
        try (Harness h = Harness.start(214)) {
            h.messageIntervalService.requestMessage(h.target, 35);

            CommandLong received = h.vehicle.awaitFrame(CommandLong.class, Duration.ofSeconds(5));
            assertEquals(MavCmd.MAV_CMD_REQUEST_MESSAGE, received.command().entry());
            assertEquals(35f, received.param1());
            h.vehicle.replyAck(MavCmd.MAV_CMD_REQUEST_MESSAGE, MavResult.MAV_RESULT_ACCEPTED);
        }
    }

    /** Wires one {@link MavlinkSession} + {@link CommandService} + {@link MessageIntervalService} + {@link FakeVehicle}. */
    private static final class Harness implements AutoCloseable {
        final MavlinkSession session;
        final MessageIntervalService messageIntervalService;
        final FakeVehicle vehicle;
        final PeerId target;

        private Harness(MavlinkSession session, MessageIntervalService messageIntervalService, FakeVehicle vehicle, PeerId target) {
            this.session = session;
            this.messageIntervalService = messageIntervalService;
            this.vehicle = vehicle;
            this.target = target;
        }

        static Harness start(int vehicleSysid) throws Exception {
            UdpListenLink listenLink = new UdpListenLink("127.0.0.1", 0);
            MavlinkSession session = new MavlinkSession(MavlinkNode.groundStation(), MavlinkCoreSettings.defaults());
            session.addLink(listenLink);
            int port = Integer.parseInt(listenLink.id().value().substring(listenLink.id().value().lastIndexOf(':') + 1));

            FakeVehicle vehicle = FakeVehicle.start("127.0.0.1", port, vehicleSysid, 1,
                    MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA, MavType.MAV_TYPE_QUADROTOR);
            PeerId target = vehicle.id();
            awaitPeerKnown(session, target, Duration.ofSeconds(10));

            CommandService commandService = new CommandService(session.sink(), session.correlator(), TIMEOUT, RETRIES);
            return new Harness(session, new MessageIntervalService(commandService), vehicle, target);
        }

        private static void awaitPeerKnown(MavlinkSession session, PeerId target, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (session.peers().peer(target) != null) {
                    return;
                }
                Thread.sleep(20);
            }
            throw new AssertionError("expected " + target + " to become known within " + timeout);
        }

        @Override
        public void close() {
            vehicle.close();
            session.close();
        }
    }
}

package com.drones.mavlink.service;

import com.drones.mavlink.PeerId;
import com.drones.mavlink.config.MavlinkCoreSettings;
import com.drones.mavlink.session.MavlinkNode;
import com.drones.mavlink.session.MavlinkSession;
import com.drones.mavlink.transport.UdpListenLink;

import io.dronefleet.mavlink.common.CommandInt;
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
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link CommandService} exercised over a real loopback {@link MavlinkSession} against
 * {@link FakeVehicle} -- see that class's own javadoc for why a continuously-heartbeating fake is
 * used rather than a one-shot one.
 */
class CommandServiceTest {

    private static final Duration TIMEOUT = Duration.ofMillis(300);
    private static final int RETRIES = 2;
    private static final Duration AWAIT = Duration.ofSeconds(10);

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void acceptedResultCompletesWithAcceptedStatus() throws Exception {
        try (Harness h = Harness.start(201)) {
            CompletableFuture<CommandService.CommandOutcome> future =
                    h.commandService.sendLong(h.target, MavCmd.MAV_CMD_DO_SET_MODE, 1f, 6f, 0, 0, 0, 0, 0);
            replyAsync(h.vehicle, MavCmd.MAV_CMD_DO_SET_MODE, MavResult.MAV_RESULT_ACCEPTED);

            CommandService.CommandOutcome outcome = future.get(AWAIT.toSeconds(), TimeUnit.SECONDS);

            assertEquals(CommandService.CommandOutcome.Status.ACCEPTED, outcome.status());
            assertEquals(MavResult.MAV_RESULT_ACCEPTED, outcome.mavResult());
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void deniedResultMapsToADenialOutcome() throws Exception {
        try (Harness h = Harness.start(202)) {
            CompletableFuture<CommandService.CommandOutcome> future =
                    h.commandService.sendLong(h.target, MavCmd.MAV_CMD_DO_SET_MODE, 1f, 6f, 0, 0, 0, 0, 0);
            replyAsync(h.vehicle, MavCmd.MAV_CMD_DO_SET_MODE, MavResult.MAV_RESULT_DENIED);

            CommandService.CommandOutcome outcome = future.get(AWAIT.toSeconds(), TimeUnit.SECONDS);

            assertEquals(CommandService.CommandOutcome.Status.DENIED, outcome.status());
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void noReplyAtAllTimesOutToNoAckAfterTheDeadline() throws Exception {
        try (Harness h = Harness.start(203)) {
            // Vehicle drains and discards every COMMAND_LONG it receives -- never acks.
            Thread drain = new Thread(() -> {
                try {
                    while (true) {
                        h.vehicle.awaitFrame(CommandLong.class, Duration.ofSeconds(5));
                    }
                } catch (Throwable ignored) {
                    // test teardown or timeout -- best-effort drain only
                }
            }, "drain-no-ack");
            drain.setDaemon(true);
            drain.start();

            CommandService.CommandOutcome outcome = h.commandService
                    .sendLong(h.target, MavCmd.MAV_CMD_DO_SET_MODE, 1f, 6f, 0, 0, 0, 0, 0)
                    .get(AWAIT.toSeconds(), TimeUnit.SECONDS);

            assertEquals(CommandService.CommandOutcome.Status.NO_ACK, outcome.status());
            assertNull(outcome.mavResult());
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void aRetryIncrementsConfirmationOnTheWire() throws Exception {
        try (Harness h = Harness.start(204)) {
            CompletableFuture<CommandService.CommandOutcome> future =
                    h.commandService.sendLong(h.target, MavCmd.MAV_CMD_DO_SET_MODE, 1f, 6f, 0, 0, 0, 0, 0);

            CommandLong first = h.vehicle.awaitFrame(CommandLong.class, Duration.ofSeconds(5));
            assertEquals(0, first.confirmation(), "the first send must carry confirmation=0");
            // Deliberately does not ack the first attempt -- let the timeout drive a retry.

            CommandLong second = h.vehicle.awaitFrame(CommandLong.class, Duration.ofSeconds(5));
            assertEquals(1, second.confirmation(), "a retry must increment confirmation");
            h.vehicle.replyAck(MavCmd.MAV_CMD_DO_SET_MODE, MavResult.MAV_RESULT_ACCEPTED);

            CommandService.CommandOutcome outcome = future.get(AWAIT.toSeconds(), TimeUnit.SECONDS);
            assertEquals(CommandService.CommandOutcome.Status.ACCEPTED, outcome.status());
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void inProgressExtendsTheDeadlineRatherThanTerminating() throws Exception {
        try (Harness h = Harness.start(205)) {
            CompletableFuture<CommandService.CommandOutcome> future =
                    h.commandService.sendLong(h.target, MavCmd.MAV_CMD_DO_SET_MODE, 1f, 6f, 0, 0, 0, 0, 0);

            CommandLong received = h.vehicle.awaitFrame(CommandLong.class, Duration.ofSeconds(5));
            assertEquals(0, received.confirmation());

            // Two IN_PROGRESS pings, each comfortably inside the previous one's TIMEOUT (300ms)
            // window with margin to spare, each re-extending it -- total elapsed before the final
            // ACCEPTED (2 * 150ms = 300ms) already equals a single un-extended window's own length,
            // so this only survives if extension genuinely kept refreshing the deadline rather than
            // the exchange getting lucky within one window.
            h.vehicle.replyAckInProgress(MavCmd.MAV_CMD_DO_SET_MODE, 25);
            Thread.sleep(150);
            h.vehicle.replyAckInProgress(MavCmd.MAV_CMD_DO_SET_MODE, 75);
            Thread.sleep(150);
            h.vehicle.replyAck(MavCmd.MAV_CMD_DO_SET_MODE, MavResult.MAV_RESULT_ACCEPTED);

            CommandService.CommandOutcome outcome = future.get(AWAIT.toSeconds(), TimeUnit.SECONDS);
            assertEquals(CommandService.CommandOutcome.Status.ACCEPTED, outcome.status());
            assertNull(h.vehicle.pollAny(Duration.ofMillis(100)), "no resend should have been observed after the ack was already sent");
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void commandIntRoundTripPreservesFrameLatLonAndAltitude() throws Exception {
        try (Harness h = Harness.start(206)) {
            int latE7 = 473977420; // ~47.397742 deg
            int lonE7 = 85455940;  // ~8.545594 deg
            float altM = 50.5f;

            CompletableFuture<CommandService.CommandOutcome> future = h.commandService.sendInt(h.target,
                    io.dronefleet.mavlink.common.MavFrame.MAV_FRAME_GLOBAL_RELATIVE_ALT_INT,
                    MavCmd.MAV_CMD_DO_REPOSITION, -1f, 0, 0, Float.NaN, latE7, lonE7, altM);

            CommandInt received = h.vehicle.awaitFrame(CommandInt.class, Duration.ofSeconds(5));
            assertEquals(io.dronefleet.mavlink.common.MavFrame.MAV_FRAME_GLOBAL_RELATIVE_ALT_INT, received.frame().entry());
            assertEquals(latE7, received.x());
            assertEquals(lonE7, received.y());
            assertEquals(altM, received.z());
            h.vehicle.replyAck(MavCmd.MAV_CMD_DO_REPOSITION, MavResult.MAV_RESULT_ACCEPTED);

            CommandService.CommandOutcome outcome = future.get(AWAIT.toSeconds(), TimeUnit.SECONDS);
            assertEquals(CommandService.CommandOutcome.Status.ACCEPTED, outcome.status());
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void ackMatchingIgnoresTargetSystemAndComponentEvenWhenUnsetOrZero() throws Exception {
        try (Harness h = Harness.start(207)) {
            // FakeVehicle#replyAck never sets targetSystem/targetComponent -- they default to 0 on
            // the wire, exactly the "unset" case plan §2.3 says must still match, since those fields
            // are extension fields not every firmware populates.
            CompletableFuture<CommandService.CommandOutcome> future =
                    h.commandService.sendLong(h.target, MavCmd.MAV_CMD_DO_SET_MODE, 1f, 6f, 0, 0, 0, 0, 0);
            replyAsync(h.vehicle, MavCmd.MAV_CMD_DO_SET_MODE, MavResult.MAV_RESULT_ACCEPTED);

            CommandService.CommandOutcome outcome = future.get(AWAIT.toSeconds(), TimeUnit.SECONDS);

            assertEquals(CommandService.CommandOutcome.Status.ACCEPTED, outcome.status());
        }
    }

    /**
     * Starts a background thread that awaits the next {@code COMMAND_LONG} the vehicle receives and
     * acks it -- started only <b>after</b> the send that will produce that {@code COMMAND_LONG} has
     * already been issued on the calling thread, so this never races ahead of it. Any error on the
     * background thread surfaces by making the eventual {@code future.get(...)} in the caller fail
     * (a NO_ACK/timeout) rather than a silently-swallowed assertion.
     */
    private static Thread replyAsync(FakeVehicle vehicle, MavCmd command, MavResult result) {
        Thread t = new Thread(() -> {
            try {
                vehicle.awaitFrame(CommandLong.class, Duration.ofSeconds(5));
                vehicle.replyAck(command, result);
            } catch (Exception ignored) {
                // surfaces indirectly: the caller's own future will time out to NO_ACK instead
            }
        }, "reply-async");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Wires one {@link MavlinkSession} + {@link CommandService} + {@link FakeVehicle} pair for one test. */
    private static final class Harness implements AutoCloseable {
        final MavlinkSession session;
        final CommandService commandService;
        final FakeVehicle vehicle;
        final PeerId target;

        private Harness(MavlinkSession session, CommandService commandService, FakeVehicle vehicle, PeerId target) {
            this.session = session;
            this.commandService = commandService;
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
            return new Harness(session, commandService, vehicle, target);
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

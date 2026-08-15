package com.drones.mavlink.api;

import com.drones.mavlink.CompId;
import com.drones.mavlink.PeerId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.config.MavlinkCoreSettings;
import com.drones.mavlink.service.CommandService;
import com.drones.mavlink.service.FakeVehicle;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link DefaultCommandGateway} over a real loopback {@link MavlinkSession} + {@link CommandService}
 * + a fake vehicle -- {@link #aLiveDuplicateSubmitReturnsTheSameStageAndSendsExactlyOneCommand} is
 * the wave's own highest-value test (plan §5.1 B3): it is what makes Kafka's at-least-once redelivery
 * safe to arm an aircraft with.
 */
class DefaultCommandGatewayTest {

    private static final Duration COMMAND_TIMEOUT = Duration.ofMillis(300);
    private static final int RETRIES = 1;
    private static final Duration DEDUPE_WINDOW = Duration.ofSeconds(5);
    private static final String VEHICLE_KEY = "vehicle-1";

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void aLiveDuplicateSubmitReturnsTheSameStageAndSendsExactlyOneCommand() throws Exception {
        try (Harness h = Harness.start(221)) {
            CommandRequest request = armRequest("corr-live", false);

            CompletionStage<CommandOutcome> first = h.gateway.submit(request);
            CompletionStage<CommandOutcome> second = h.gateway.submit(request); // still live -- vehicle hasn't acked yet
            assertSame(first, second, "a live repeat must return the identical stage, not a new dispatch");

            h.vehicle.awaitFrame(CommandLong.class, Duration.ofSeconds(5));
            h.vehicle.replyAck(MavCmd.MAV_CMD_COMPONENT_ARM_DISARM, MavResult.MAV_RESULT_ACCEPTED);

            CommandOutcome outcome = first.toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(CommandOutcome.Status.ACCEPTED, outcome.status());

            assertNull(h.vehicle.pollAny(Duration.ofMillis(300)),
                    "exactly one COMMAND_LONG must ever reach the wire for this correlationId");
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void resubmittingAfterCompletionReturnsDuplicateAndSendsNothing() throws Exception {
        try (Harness h = Harness.start(222)) {
            CommandRequest request = armRequest("corr-completed", false);

            CompletionStage<CommandOutcome> first = h.gateway.submit(request);
            h.vehicle.awaitFrame(CommandLong.class, Duration.ofSeconds(5));
            h.vehicle.replyAck(MavCmd.MAV_CMD_COMPONENT_ARM_DISARM, MavResult.MAV_RESULT_ACCEPTED);
            CommandOutcome accepted = first.toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(CommandOutcome.Status.ACCEPTED, accepted.status());

            CommandOutcome duplicate = h.gateway.submit(request).toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(CommandOutcome.Status.DUPLICATE, duplicate.status());
            assertNull(h.vehicle.pollAny(Duration.ofMillis(300)), "a completed-duplicate resubmit must not touch the wire");
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void aDifferentCorrelationIdForTheSameVehicleGoesThroughNormally() throws Exception {
        try (Harness h = Harness.start(223)) {
            CommandRequest requestA = armRequest("corr-a", false);
            CompletionStage<CommandOutcome> stageA = h.gateway.submit(requestA);
            h.vehicle.awaitFrame(CommandLong.class, Duration.ofSeconds(5));
            h.vehicle.replyAck(MavCmd.MAV_CMD_COMPONENT_ARM_DISARM, MavResult.MAV_RESULT_ACCEPTED);
            assertEquals(CommandOutcome.Status.ACCEPTED, stageA.toCompletableFuture().get(10, TimeUnit.SECONDS).status());

            CommandRequest requestB = armRequest("corr-b", false);
            CompletionStage<CommandOutcome> stageB = h.gateway.submit(requestB);
            h.vehicle.awaitFrame(CommandLong.class, Duration.ofSeconds(5)); // a genuinely new COMMAND_LONG
            h.vehicle.replyAck(MavCmd.MAV_CMD_COMPONENT_ARM_DISARM, MavResult.MAV_RESULT_ACCEPTED);
            assertEquals(CommandOutcome.Status.ACCEPTED, stageB.toCompletableFuture().get(10, TimeUnit.SECONDS).status());
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void submitCompletesUnreachableForAnUnknownVehicleKey() throws Exception {
        try (Harness h = Harness.start(224)) {
            CommandRequest request = new CommandRequest("no-such-vehicle", "ARM", List.of(), "corr-unknown",
                    Duration.ofSeconds(2), false);

            CommandOutcome outcome = h.gateway.submit(request).toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(CommandOutcome.Status.UNREACHABLE, outcome.status());
            assertNull(h.vehicle.pollAny(Duration.ofMillis(100)), "an unknown vehicle key must never touch the wire");
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void submitCompletesUnreachableForAResolvableButNeverHeardPeer() throws Exception {
        try (Harness h = Harness.start(225)) {
            PeerId neverHeard = new PeerId(new SysId(250), new CompId(1)); // resolvable, but this session has heard nothing from it
            h.resolver.map("ghost-vehicle", neverHeard);
            CommandRequest request = new CommandRequest("ghost-vehicle", "ARM", List.of(), "corr-ghost",
                    Duration.ofSeconds(2), false);

            CommandOutcome outcome = h.gateway.submit(request).toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(CommandOutcome.Status.UNREACHABLE, outcome.status());
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void submitThrowsSynchronouslyForAMalformedKind() throws Exception {
        try (Harness h = Harness.start(226)) {
            CommandRequest request = new CommandRequest(VEHICLE_KEY, "NOT_A_REAL_KIND", List.of(), "corr-bad-kind",
                    Duration.ofSeconds(2), false);

            assertThrows(IllegalArgumentException.class, () -> h.gateway.submit(request));
            assertNull(h.vehicle.pollAny(Duration.ofMillis(100)), "a malformed request must never reach the wire");
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void rtlKindSendsTheSpecsOwnReturnToLaunchCommandWithNoParamsRequired() throws Exception {
        try (Harness h = Harness.start(227)) {
            CommandRequest request = new CommandRequest(VEHICLE_KEY, "RTL", List.of(), "corr-rtl", Duration.ofSeconds(2), false);

            CompletionStage<CommandOutcome> stage = h.gateway.submit(request);
            CommandLong received = h.vehicle.awaitFrame(CommandLong.class, Duration.ofSeconds(5));
            assertEquals(MavCmd.MAV_CMD_NAV_RETURN_TO_LAUNCH, received.command().entry());
            h.vehicle.replyAck(MavCmd.MAV_CMD_NAV_RETURN_TO_LAUNCH, MavResult.MAV_RESULT_ACCEPTED);

            assertEquals(CommandOutcome.Status.ACCEPTED, stage.toCompletableFuture().get(10, TimeUnit.SECONDS).status());
        }
    }

    private static CommandRequest armRequest(String correlationId, boolean force) {
        return new CommandRequest(VEHICLE_KEY, "ARM", List.of(), correlationId, Duration.ofSeconds(2), force);
    }

    /** A settable {@link VehicleKeyResolver} the test wires up directly. */
    private static final class MapVehicleKeyResolver implements VehicleKeyResolver {
        private final Map<String, PeerId> byKey = new java.util.concurrent.ConcurrentHashMap<>();

        void map(String key, PeerId id) {
            byKey.put(key, id);
        }

        @Override
        public PeerId resolve(String vehicleKey) {
            return byKey.get(vehicleKey);
        }
    }

    /** Wires one {@link MavlinkSession} + {@link CommandService} + {@link DefaultCommandGateway} + a fake vehicle. */
    private static final class Harness implements AutoCloseable {
        final MavlinkSession session;
        final DefaultCommandGateway gateway;
        final MapVehicleKeyResolver resolver;
        final FakeVehicle vehicle;

        private Harness(MavlinkSession session, DefaultCommandGateway gateway, MapVehicleKeyResolver resolver,
                         FakeVehicle vehicle) {
            this.session = session;
            this.gateway = gateway;
            this.resolver = resolver;
            this.vehicle = vehicle;
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

            MapVehicleKeyResolver resolver = new MapVehicleKeyResolver();
            resolver.map(VEHICLE_KEY, target);

            CommandService commandService = new CommandService(session.sink(), session.correlator(), COMMAND_TIMEOUT, RETRIES);
            DefaultCommandGateway gateway = new DefaultCommandGateway(commandService, resolver, DEDUPE_WINDOW);
            return new Harness(session, gateway, resolver, vehicle);
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

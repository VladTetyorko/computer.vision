package com.drones.vision.adapter.mavlink;

import com.drones.mavlink.CompId;
import com.drones.mavlink.PeerId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.codec.FrameSink;
import com.drones.mavlink.codec.MavFrame;
import com.drones.mavlink.codec.MavHeader;
import com.drones.mavlink.service.CommandService;
import com.drones.mavlink.session.Correlator;
import com.drones.mavlink.session.Dispatcher;
import com.drones.mavlink.session.MatchKey;
import com.drones.mavlink.session.MessageFilter;
import com.drones.mavlink.session.Subscription;
import com.drones.mavlink.transport.LinkId;
import com.drones.mavlink.transport.LinkPeer;

import io.dronefleet.mavlink.common.CommandAck;
import io.dronefleet.mavlink.common.CommandLong;
import io.dronefleet.mavlink.common.MavCmd;
import io.dronefleet.mavlink.common.MavResult;
import io.dronefleet.mavlink.minimal.Heartbeat;
import io.dronefleet.mavlink.util.EnumValue;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast, deterministic coverage of {@link MavlinkConnectRemediator}'s own bookkeeping -- fed frames
 * directly through a hand-fake {@link Dispatcher}/{@link FrameSink}/{@link Correlator}, no socket, no
 * thread, no wall-clock dependency (silence is simulated via explicit {@link MavFrame#receivedAt()}
 * timestamps, the same technique {@link MavlinkMessageInventoryTest} uses).
 *
 * <p>{@link MavlinkConnectRemediationIntegrationTest} is the required real-loopback proof that a
 * genuine peer over UDP gets exactly this behaviour, including the flag-off "zero commands sent"
 * guarantee; {@link MavlinkSitlOnConnectIntegrationTest} is the docker-gated proof against real
 * ArduPilot firmware. This class exists because those two are slow and cannot cheaply exercise every
 * branch (silence-window arithmetic, the empty-message-set no-op case, the exact wire params sent).
 */
class MavlinkConnectRemediatorTest {

    private static final int TARGET_COMPONENT_AUTOPILOT = MavlinkFlightCommander.TARGET_COMPONENT_AUTOPILOT;

    @Test
    void requestsAreSentOneAtATimeNeverConcurrentlyToTheSamePeer() {
        FakeDispatcher dispatcher = new FakeDispatcher();
        FakeSink sink = new FakeSink();
        FakeCorrelator correlator = new FakeCorrelator();
        MavlinkSettings.Onboarding.MessageRequest r1 = request(1, 500);
        MavlinkSettings.Onboarding.MessageRequest r2 = request(30, 200);
        MavlinkSettings.Onboarding.MessageRequest r3 = request(74, 1000);
        MavlinkSettings settings = settingsWith(true, List.of(r1, r2, r3));

        new MavlinkConnectRemediator(dispatcher, sink, correlator, settings);
        dispatcher.feed(heartbeatFrame(9, Instant.now()));

        // Only the first of the three may be outstanding at once -- COMMAND_ACK correlates purely
        // on (sysid, command id), so sending a second before the first resolves would collide on the
        // same Correlator key. If MavlinkConnectRemediator ever regresses to a naive fire-and-forget
        // loop, either this assertion fails (more than one sent immediately) or FakeCorrelator itself
        // throws IllegalStateException on the second registration -- both catch the regression.
        assertEquals(1, sink.sent.size(), "only the first request may be in flight at once");
        assertRequest(sink.sent.get(0), 9, r1);

        correlator.ack(keyFor(9));
        assertEquals(2, sink.sent.size(), "acking the first request must release the second");
        assertRequest(sink.sent.get(1), 9, r2);

        correlator.ack(keyFor(9));
        assertEquals(3, sink.sent.size());
        assertRequest(sink.sent.get(2), 9, r3);

        correlator.ack(keyFor(9));
        assertEquals(3, sink.sent.size(), "no more than the configured three requests are ever sent");
    }

    @Test
    void aSecondFrameFromTheSamePeerSoonAfterSendsNothingMore() {
        FakeDispatcher dispatcher = new FakeDispatcher();
        FakeSink sink = new FakeSink();
        FakeCorrelator correlator = new FakeCorrelator();
        MavlinkSettings settings = settingsWith(true, List.of(request(74, 500)));
        new MavlinkConnectRemediator(dispatcher, sink, correlator, settings);

        Instant t0 = Instant.now();
        dispatcher.feed(heartbeatFrame(3, t0));
        assertEquals(1, sink.sent.size());
        correlator.ack(keyFor(3));

        dispatcher.feed(heartbeatFrame(3, t0.plusMillis(200))); // well inside the (30s default) silence window
        dispatcher.feed(heartbeatFrame(3, t0.plusMillis(400)));

        assertEquals(1, sink.sent.size(), "a peer heard continuously must not be re-remediated on every frame");
    }

    @Test
    void aPeerSilentLongerThanTheSilenceWindowIsTreatedAsNewlyLearnedAgain() {
        FakeDispatcher dispatcher = new FakeDispatcher();
        FakeSink sink = new FakeSink();
        FakeCorrelator correlator = new FakeCorrelator();
        MavlinkSettings settings = settingsWith(true, List.of(request(74, 500)))
                .withSilenceWindow(Duration.ofMillis(50));
        new MavlinkConnectRemediator(dispatcher, sink, correlator, settings);

        Instant t0 = Instant.now();
        dispatcher.feed(heartbeatFrame(5, t0));
        assertEquals(1, sink.sent.size());
        correlator.ack(keyFor(5));

        // Just under the window: still the same "connection".
        dispatcher.feed(heartbeatFrame(5, t0.plusMillis(40)));
        assertEquals(1, sink.sent.size(), "40ms of silence is under the 50ms window -- must not re-fire");
        correlator.ackIfPending(keyFor(5)); // no-op if nothing is pending

        // Well past the window from the last-seen time: a fresh "connection" by this class's own rule.
        dispatcher.feed(heartbeatFrame(5, t0.plusMillis(200)));
        assertEquals(2, sink.sent.size(),
                "160ms of silence exceeds the 50ms window -- must be treated as newly learned again");
    }

    @Test
    void distinctPeersAreRemediatedIndependently() {
        FakeDispatcher dispatcher = new FakeDispatcher();
        FakeSink sink = new FakeSink();
        FakeCorrelator correlator = new FakeCorrelator();
        MavlinkSettings settings = settingsWith(true, List.of(request(74, 500)));
        new MavlinkConnectRemediator(dispatcher, sink, correlator, settings);

        Instant now = Instant.now();
        dispatcher.feed(heartbeatFrame(11, now));
        dispatcher.feed(heartbeatFrame(22, now));

        assertEquals(2, sink.sent.size(), "two distinct sysids must each get their own request");
        assertTrue(sink.sent.stream().anyMatch(c -> c.targetSystem() == 11));
        assertTrue(sink.sent.stream().anyMatch(c -> c.targetSystem() == 22));
    }

    @Test
    void anEmptyConfiguredMessageSetSendsNothingEvenForANewPeer() {
        FakeDispatcher dispatcher = new FakeDispatcher();
        FakeSink sink = new FakeSink();
        FakeCorrelator correlator = new FakeCorrelator();
        MavlinkSettings settings = settingsWith(true, List.of());
        new MavlinkConnectRemediator(dispatcher, sink, correlator, settings);

        dispatcher.feed(heartbeatFrame(1, Instant.now()));

        assertTrue(sink.sent.isEmpty(), "an empty message set is a legal no-op configuration");
    }

    @Test
    void constructorRejectsNullCollaborators() {
        FakeDispatcher dispatcher = new FakeDispatcher();
        FakeSink sink = new FakeSink();
        FakeCorrelator correlator = new FakeCorrelator();
        MavlinkSettings settings = settingsWith(true, List.of(request(74, 500)));

        assertThrows(NullPointerException.class, () -> new MavlinkConnectRemediator(null, sink, correlator, settings));
        assertThrows(NullPointerException.class, () -> new MavlinkConnectRemediator(dispatcher, null, correlator, settings));
        assertThrows(NullPointerException.class, () -> new MavlinkConnectRemediator(dispatcher, sink, null, settings));
        assertThrows(NullPointerException.class, () -> new MavlinkConnectRemediator(dispatcher, sink, correlator, null));
    }

    private static MavlinkSettings.Onboarding.MessageRequest request(int messageId, long intervalMillis) {
        return new MavlinkSettings.Onboarding.MessageRequest(messageId, Duration.ofMillis(intervalMillis));
    }

    private static MavlinkSettings settingsWith(boolean enabled, List<MavlinkSettings.Onboarding.MessageRequest> requests) {
        return MavlinkSettings.defaults().withOnboarding(new MavlinkSettings.Onboarding(
                List.of(), Duration.ofSeconds(1), 1, Duration.ofSeconds(1), 1, enabled, requests));
    }

    private static MatchKey keyFor(int sysid) {
        return new MatchKey(new SysId(sysid), CommandService.COMMAND_ACK_MESSAGE_ID,
                EnumValue.of(MavCmd.MAV_CMD_SET_MESSAGE_INTERVAL).value());
    }

    private static void assertRequest(CommandLong sent, int sysid, MavlinkSettings.Onboarding.MessageRequest expected) {
        assertEquals(MavCmd.MAV_CMD_SET_MESSAGE_INTERVAL, sent.command().entry());
        assertEquals(sysid, sent.targetSystem());
        assertEquals(TARGET_COMPONENT_AUTOPILOT, sent.targetComponent());
        assertEquals((float) expected.messageId(), sent.param1());
        float expectedMicros = expected.interval().toNanos() / (float) TimeUnit.MICROSECONDS.toNanos(1);
        assertEquals(expectedMicros, sent.param2());
    }

    private static MavFrame heartbeatFrame(int sysid, Instant receivedAt) {
        MavHeader header = new MavHeader(2, 0, new SysId(sysid), new CompId(1), 0, 0, 0, false);
        return new MavFrame(header, Heartbeat.builder().build(), new LinkId("test-link"),
                new LinkPeer("127.0.0.1", 14550), receivedAt);
    }

    /** Captures the one handler {@link MavlinkGateway}/{@link MavlinkConnectRemediator} would register. */
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

    /** Records every {@code COMMAND_LONG} sent, in order; never completes anything on its own. */
    private static final class FakeSink implements FrameSink {
        final List<CommandLong> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(Object payload, PeerId target) {
            sent.add((CommandLong) payload);
        }

        @Override
        public void broadcast(Object payload, LinkId link) {
        }
    }

    /**
     * A faithful-enough {@link Correlator}: exclusive registration (throws on a duplicate live
     * waiter, exactly like {@code DefaultCorrelator}), and a test-driven {@link #ack} to simulate the
     * vehicle's own asynchronous {@code COMMAND_ACK} arriving on its own schedule -- never
     * synchronously inside {@link FakeSink#send}, which would hide the very race the production code
     * must guard against.
     */
    private static final class FakeCorrelator implements Correlator {
        private final Map<MatchKey, CompletableFuture<MavFrame>> waiters = new ConcurrentHashMap<>();

        @Override
        public CompletableFuture<MavFrame> await(MatchKey key, Duration timeout) {
            CompletableFuture<MavFrame> future = new CompletableFuture<>();
            if (waiters.putIfAbsent(key, future) != null) {
                throw new IllegalStateException("duplicate live await for " + key
                        + " -- MavlinkConnectRemediator must serialise requests to one peer");
            }
            return future;
        }

        @Override
        public void cancel(MatchKey key) {
            waiters.remove(key);
        }

        void ack(MatchKey key) {
            CompletableFuture<MavFrame> future = waiters.remove(key);
            assertNotNull(future, "no live await for " + key + " to ack");
            future.complete(ackFrame(key));
        }

        void ackIfPending(MatchKey key) {
            CompletableFuture<MavFrame> future = waiters.remove(key);
            if (future != null) {
                future.complete(ackFrame(key));
            }
        }

        private static MavFrame ackFrame(MatchKey key) {
            CommandAck ack = CommandAck.builder()
                    .command(MavCmd.MAV_CMD_SET_MESSAGE_INTERVAL)
                    .result(MavResult.MAV_RESULT_ACCEPTED)
                    .build();
            MavHeader header = new MavHeader(2, 0, key.system(), new CompId(TARGET_COMPONENT_AUTOPILOT),
                    CommandService.COMMAND_ACK_MESSAGE_ID, 0, 0, false);
            return new MavFrame(header, ack, new LinkId("test-link"), new LinkPeer("127.0.0.1", 14550), Instant.now());
        }
    }
}

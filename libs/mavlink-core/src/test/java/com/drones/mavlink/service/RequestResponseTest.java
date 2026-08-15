package com.drones.mavlink.service;

import com.drones.mavlink.CompId;
import com.drones.mavlink.PeerId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.codec.FrameSink;
import com.drones.mavlink.codec.MavFrame;
import com.drones.mavlink.codec.MavHeader;
import com.drones.mavlink.session.Correlator;
import com.drones.mavlink.session.MatchKey;
import com.drones.mavlink.transport.LinkId;
import com.drones.mavlink.transport.LinkPeer;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct unit tests of {@link RequestResponse} -- the DRY Family-A machine {@link CommandService}
 * (and, from W6, Mission/FTP) is built on. No sockets, no real {@code MavlinkSession}: a small
 * in-memory {@link FakeCorrelator} (deliberately not {@code DefaultCorrelator}, whose {@code offer}
 * only ever recognizes {@code CommandAck} payloads -- this test wants to drive matching directly with
 * plain marker payloads) paired with a hand-written {@link FrameSink}, so retry/extension timing is
 * exercised with short, deterministic durations rather than a real network round trip.
 */
class RequestResponseTest {

    private static final LinkId LINK = new LinkId("test-link");
    private static final SysId VEHICLE = new SysId(9);
    private static final PeerId TARGET = new PeerId(VEHICLE, new CompId(1));

    @Test
    void silenceRetriesUpToMaxRetriesThenFailsWithTheLastTimeout() throws Exception {
        FakeCorrelator correlator = new FakeCorrelator();
        List<Integer> sentAttempts = new CopyOnWriteArrayList<>();
        FrameSink sink = new SendSink((payload, target) -> sentAttempts.add((Integer) payload));
        RequestResponse requestResponse = new RequestResponse(correlator, sink);
        MatchKey key = key(1L);

        CompletableFuture<MavFrame> future = requestResponse.exchange(TARGET, key, Duration.ofMillis(40), 2,
                attempt -> attempt, reply -> null);

        ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
        assertTrue(e.getCause() instanceof TimeoutException, "expected a TimeoutException, got " + e.getCause());
        assertEquals(List.of(0, 1, 2), sentAttempts, "expected one initial send plus two retries, attempts 0,1,2");
    }

    @Test
    void aTerminalReplyCompletesImmediatelyWithoutAnyRetry() throws Exception {
        FakeCorrelator correlator = new FakeCorrelator();
        List<Integer> sentAttempts = new CopyOnWriteArrayList<>();
        MatchKey key = key(1L);
        FrameSink sink = new SendSink((payload, target) -> {
            sentAttempts.add((Integer) payload);
            correlator.offer(key, frameOf(key, "terminal"));
        });
        RequestResponse requestResponse = new RequestResponse(correlator, sink);

        CompletableFuture<MavFrame> future = requestResponse.exchange(TARGET, key, Duration.ofSeconds(2), 2,
                attempt -> attempt, reply -> null);

        MavFrame result = future.get(2, TimeUnit.SECONDS);
        assertEquals("terminal", result.payload());
        assertEquals(List.of(0), sentAttempts, "a terminal reply must not trigger any retry");
    }

    @Test
    void aClassifierExtensionKeepsWaitingWithoutResendingUntilATerminalReplyArrives() throws Exception {
        FakeCorrelator correlator = new FakeCorrelator();
        List<Integer> sentAttempts = new CopyOnWriteArrayList<>();
        MatchKey key = key(2L);
        FrameSink sink = new SendSink((payload, target) -> {
            sentAttempts.add((Integer) payload);
            // Simulate the vehicle: first an IN_PROGRESS-style reply shortly after the send, then a
            // terminal reply well within the extension window -- neither should count as a retry.
            delayed(() -> correlator.offer(key, frameOf(key, "in-progress")), 15);
            delayed(() -> correlator.offer(key, frameOf(key, "terminal")), 60);
        });
        RequestResponse requestResponse = new RequestResponse(correlator, sink);

        CompletableFuture<MavFrame> future = requestResponse.exchange(TARGET, key, Duration.ofMillis(30), 2,
                attempt -> attempt, reply -> "in-progress".equals(reply.payload()) ? Duration.ofMillis(200) : null);

        MavFrame result = future.get(3, TimeUnit.SECONDS);
        assertEquals("terminal", result.payload());
        assertEquals(List.of(0), sentAttempts, "an extension must never trigger a resend");
    }

    @Test
    void extensionWindowTimingOutIsATerminalFailureNotARetry() throws Exception {
        FakeCorrelator correlator = new FakeCorrelator();
        List<Integer> sentAttempts = new CopyOnWriteArrayList<>();
        MatchKey key = key(3L);
        FrameSink sink = new SendSink((payload, target) -> {
            sentAttempts.add((Integer) payload);
            delayed(() -> correlator.offer(key, frameOf(key, "in-progress")), 10);
            // deliberately never offers a terminal reply -- the extension window itself must time out
        });
        RequestResponse requestResponse = new RequestResponse(correlator, sink);

        CompletableFuture<MavFrame> future = requestResponse.exchange(TARGET, key, Duration.ofMillis(30), 2,
                attempt -> attempt, reply -> "in-progress".equals(reply.payload()) ? Duration.ofMillis(40) : null);

        ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
        assertTrue(e.getCause() instanceof TimeoutException, "expected a TimeoutException, got " + e.getCause());
        assertEquals(List.of(0), sentAttempts, "a timed-out extension must not resend -- it is a terminal failure");
    }

    @Test
    void aSendFailureFailsTheExchangeAndReleasesTheWaiter() throws Exception {
        FakeCorrelator correlator = new FakeCorrelator();
        MatchKey key = key(4L);
        RuntimeException boom = new RuntimeException("send boom");
        FrameSink sink = new SendSink((payload, target) -> {
            throw boom;
        });
        RequestResponse requestResponse = new RequestResponse(correlator, sink);

        CompletableFuture<MavFrame> future =
                requestResponse.exchange(TARGET, key, Duration.ofMillis(50), 0, attempt -> attempt, reply -> null);

        ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        assertTrue(e.getCause() == boom, "expected the exchange to fail with the send()-thrown exception itself");

        // The waiter must have been released (cancel()), not left dangling -- a fresh await for the
        // same key must succeed rather than throw IllegalStateException.
        correlator.await(key, Duration.ofMillis(50)).cancel(false);
    }

    private static MatchKey key(long discriminator) {
        return new MatchKey(VEHICLE, 77, discriminator);
    }

    private static void delayed(Runnable action, long delayMillis) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            action.run();
        }, "request-response-test-offer");
        t.setDaemon(true);
        t.start();
    }

    private static MavFrame frameOf(MatchKey key, Object payload) {
        MavHeader header = new MavHeader(2, 0, key.system(), new CompId(1), key.messageId(), 0, 0, false);
        return new MavFrame(header, payload, LINK, LinkPeer.NONE, Instant.now());
    }

    /** {@link FrameSink} has two abstract methods -- not a lambda target; this wraps only {@code send}. */
    private static final class SendSink implements FrameSink {
        private final BiConsumer<Object, PeerId> onSend;

        SendSink(BiConsumer<Object, PeerId> onSend) {
            this.onSend = onSend;
        }

        @Override
        public void send(Object payload, PeerId target) {
            onSend.accept(payload, target);
        }

        @Override
        public void broadcast(Object payload, LinkId link) {
            throw new UnsupportedOperationException("not used by these tests");
        }
    }

    /**
     * A {@link Correlator} this test drives directly via {@link #offer} -- unlike
     * {@code DefaultCorrelator}, whose extraction only ever recognizes a {@code CommandAck} payload,
     * this fake matches purely on {@link MatchKey} so the test can use plain marker payloads.
     */
    private static final class FakeCorrelator implements Correlator {
        private final Map<MatchKey, CompletableFuture<MavFrame>> waiters = new ConcurrentHashMap<>();

        @Override
        public CompletableFuture<MavFrame> await(MatchKey key, Duration timeout) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(timeout, "timeout");
            CompletableFuture<MavFrame> future = new CompletableFuture<>();
            if (waiters.putIfAbsent(key, future) != null) {
                throw new IllegalStateException("A live await is already registered for " + key);
            }
            future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
            future.whenComplete((frame, error) -> waiters.remove(key, future));
            return future;
        }

        @Override
        public void cancel(MatchKey key) {
            CompletableFuture<MavFrame> removed = waiters.remove(key);
            if (removed != null) {
                removed.cancel(false);
            }
        }

        void offer(MatchKey key, MavFrame frame) {
            CompletableFuture<MavFrame> future = waiters.remove(key);
            if (future != null) {
                future.complete(frame);
            }
        }
    }
}

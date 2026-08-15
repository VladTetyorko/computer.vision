package com.drones.mavlink.service;

import com.drones.mavlink.CompId;
import com.drones.mavlink.PeerId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.codec.FrameSink;
import com.drones.mavlink.session.DefaultTxScheduler;
import com.drones.mavlink.session.Peer;
import com.drones.mavlink.session.PeerDirectory;
import com.drones.mavlink.transport.LinkId;
import com.drones.mavlink.transport.LinkPeer;

import io.dronefleet.mavlink.minimal.Heartbeat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HeartbeatService} tests use a real {@link DefaultTxScheduler} with a fast injected period
 * (matching {@code DefaultTxSchedulerTest}'s own "assert a fired count over a window, never a
 * wall-clock Hz" instruction) paired with hand-written {@link PeerDirectory}/{@link FrameSink} fakes
 * -- no sockets needed for either concern this class owns.
 */
class HeartbeatServiceTest {

    private static final Duration PERIOD = Duration.ofMillis(20);
    private static final Duration PEER_TIMEOUT = Duration.ofMillis(80);

    private DefaultTxScheduler scheduler;

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.close();
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void emitsRoughlyAtTheConfiguredPeriod() throws Exception {
        CountingFrameSink sink = new CountingFrameSink();
        FakePeerDirectory peers = FakePeerDirectory.knowing(peer(1, "link-a"));
        scheduler = new DefaultTxScheduler(Duration.ofSeconds(2));
        HeartbeatService service = new HeartbeatService(sink, scheduler, peers, PERIOD, PEER_TIMEOUT, HeartbeatContent.groundStation());

        service.start();
        awaitAtLeast(sink.broadcastCount, 5, Duration.ofSeconds(3));
        service.stop();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void aPeerFlipsToDisconnectedAfterPeerTimeoutElapsesWithoutBeingHeardAgain() throws Exception {
        CountingFrameSink sink = new CountingFrameSink();
        PeerId id = new PeerId(new SysId(5), new CompId(1));
        FakePeerDirectory peers = FakePeerDirectory.knowing(peerAt(id, "link-a", Instant.now()));
        scheduler = new DefaultTxScheduler(Duration.ofSeconds(2));
        HeartbeatService service = new HeartbeatService(sink, scheduler, peers, PERIOD, PEER_TIMEOUT, HeartbeatContent.groundStation());

        service.start();
        awaitTrue(() -> service.isConnected(id), Duration.ofSeconds(2), "expected the peer to be reported connected initially");

        // Never refresh lastHeard again -- past peerTimeout, the next tick must flip it.
        awaitTrue(() -> !service.isConnected(id), Duration.ofSeconds(3), "expected the peer to flip disconnected after peerTimeout");
        service.stop();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void aPeerReconnectingIsReflectedOnceItIsHeardFromAgain() throws Exception {
        CountingFrameSink sink = new CountingFrameSink();
        PeerId id = new PeerId(new SysId(6), new CompId(1));
        FakePeerDirectory peers = FakePeerDirectory.knowing(peerAt(id, "link-a", Instant.now()));
        scheduler = new DefaultTxScheduler(Duration.ofSeconds(2));
        HeartbeatService service = new HeartbeatService(sink, scheduler, peers, PERIOD, PEER_TIMEOUT, HeartbeatContent.groundStation());

        service.start();
        awaitTrue(() -> !service.isConnected(id), Duration.ofSeconds(3), "expected disconnection first");

        peers.refresh(id, "link-a"); // a fresh frame arrives
        awaitTrue(() -> service.isConnected(id), Duration.ofSeconds(3), "expected reconnection to be observed");
        service.stop();
    }

    @Test
    void isConnectedIsFalseForAnUnknownPeer() {
        FakePeerDirectory peers = new FakePeerDirectory();
        HeartbeatService service = new HeartbeatService(new CountingFrameSink(), new DefaultTxScheduler(Duration.ofSeconds(1)),
                peers, PERIOD, PEER_TIMEOUT, HeartbeatContent.groundStation());
        try {
            assertFalse(service.isConnected(new PeerId(new SysId(99), new CompId(1))));
        } finally {
            service.stop();
        }
    }

    private static void awaitAtLeast(AtomicInteger counter, int target, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (counter.get() >= target) {
                return;
            }
            Thread.sleep(5);
        }
        assertTrue(counter.get() >= target, "expected at least " + target + " broadcasts within " + timeout + ", got " + counter.get());
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, Duration timeout, String message) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean(), message);
    }

    private static Peer peer(int sysid, String linkValue) {
        return peerAt(new PeerId(new SysId(sysid), new CompId(1)), linkValue, Instant.now());
    }

    private static Peer peerAt(PeerId id, String linkValue, Instant lastHeard) {
        return new Peer(id, new LinkId(linkValue), LinkPeer.NONE, lastHeard, lastHeard, null);
    }

    private static final class CountingFrameSink implements FrameSink {
        final AtomicInteger broadcastCount = new AtomicInteger();

        @Override
        public void send(Object payload, PeerId target) {
            throw new UnsupportedOperationException("HeartbeatService only ever broadcasts");
        }

        @Override
        public void broadcast(Object payload, LinkId link) {
            if (payload instanceof Heartbeat) {
                broadcastCount.incrementAndGet();
            }
        }
    }

    /** A {@link PeerDirectory} the test can mutate freely, unlike the real (append-only) one. */
    private static final class FakePeerDirectory implements PeerDirectory {
        private final Map<PeerId, Peer> peers = new ConcurrentHashMap<>();

        static FakePeerDirectory knowing(Peer peer) {
            FakePeerDirectory directory = new FakePeerDirectory();
            directory.peers.put(peer.id(), peer);
            return directory;
        }

        void refresh(PeerId id, String linkValue) {
            peers.put(id, peerAt(id, linkValue, Instant.now()));
        }

        @Override
        public java.util.Collection<Peer> peers() {
            return List.copyOf(peers.values());
        }

        @Override
        public Peer peer(PeerId id) {
            return peers.get(id);
        }

        @Override
        public List<Peer> peersOnLink(LinkId link) {
            return peers.values().stream().filter(p -> p.link().equals(link)).toList();
        }
    }
}

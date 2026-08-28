package com.drones.mavlink.session;

import com.drones.mavlink.config.MavlinkCoreSettings;
import com.drones.mavlink.transport.ByteChunk;
import com.drones.mavlink.transport.LinkId;
import com.drones.mavlink.transport.LinkPeer;
import com.drones.mavlink.transport.MavlinkLink;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FLEET-RADIO-PLAN.md F7/R4: {@link MavlinkSession#onLinkFailure} must fire for a genuine
 * {@link MavlinkLink#poll} {@link IOException}, and must <b>not</b> fire once the link is already
 * shutting down. Before R4, {@code runLoop} caught this exact exception, logged a WARNING and
 * silently returned -- telling nobody. A hand-built {@link MavlinkLink} test double is used rather
 * than a real socket because forcing a real transport to throw {@link IOException} on demand -- as
 * opposed to returning {@code null} on close, which every real implementation in this module
 * honors (see {@link MavlinkLink#poll}'s own contract) -- is exactly the "non-conforming/failed
 * link" case this test needs to control precisely and deterministically.
 */
class MavlinkSessionLinkFailureTest {

    @Test
    void aGenuinePollFailureFiresTheListenerWithTheLinkIdAndCause() throws Exception {
        ThrowingLink link = new ThrowingLink();
        MavlinkSession session = new MavlinkSession(MavlinkNode.groundStation(), MavlinkCoreSettings.defaults());
        try {
            CountDownLatch notified = new CountDownLatch(1);
            AtomicReference<LinkId> notifiedLinkId = new AtomicReference<>();
            AtomicReference<IOException> notifiedCause = new AtomicReference<>();
            session.onLinkFailure((id, cause) -> {
                notifiedLinkId.set(id);
                notifiedCause.set(cause);
                notified.countDown();
            });

            session.addLink(link);
            link.releaseToThrow();

            assertTrue(notified.await(5, TimeUnit.SECONDS), "the listener must fire for a genuine poll() IOException");
            assertEquals(link.id(), notifiedLinkId.get());
            assertSame(link.failure, notifiedCause.get());
        } finally {
            session.close();
        }
    }

    @Test
    void aPollFailureRacingWithShutdownDoesNotFireTheListener() throws Exception {
        ThrowingLink link = new ThrowingLink();
        MavlinkSession session = new MavlinkSession(MavlinkNode.groundStation(),
                MavlinkCoreSettings.defaults().withCloseJoinTimeout(Duration.ofSeconds(5)));
        try {
            AtomicBoolean notified = new AtomicBoolean(false);
            session.onLinkFailure((id, cause) -> notified.set(true));
            session.addLink(link);

            // removeLink() flips LinkRuntime.running to false (synchronously, before joining the
            // reader thread) and only then blocks on that join -- release the parked poll() only
            // after that has almost certainly already happened, so the IOException reaches
            // runLoop's catch block with running already false, exactly like a link that fails
            // the instant after this session started tearing it down.
            Thread removeThread = new Thread(() -> session.removeLink(link.id()));
            removeThread.start();
            Thread.sleep(300);
            link.releaseToThrow();
            removeThread.join(TimeUnit.SECONDS.toMillis(5));

            assertFalse(removeThread.isAlive(), "removeLink() must have returned once the reader thread unblocked");
            assertFalse(notified.get(), "a link failure racing with shutdown must not be reported as a failure");
        } finally {
            session.close();
        }
    }

    /** Blocks every {@link #poll} until released, then always throws the same {@link IOException}. */
    private static final class ThrowingLink implements MavlinkLink {
        private final LinkId id = new LinkId("throwing-test-link");
        private final CountDownLatch releaseGate = new CountDownLatch(1);
        final IOException failure = new IOException("simulated read failure");

        @Override
        public LinkId id() {
            return id;
        }

        @Override
        public boolean preservesMessageBoundaries() {
            return true;
        }

        @Override
        public ByteChunk poll(Duration timeout) throws IOException {
            try {
                releaseGate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            throw failure;
        }

        @Override
        public void send(byte[] frame, int off, int len, LinkPeer target) {
            // not exercised by this test
        }

        @Override
        public LinkPeer defaultTarget() {
            return LinkPeer.NONE;
        }

        @Override
        public void close() {
            // Deliberately does not release the gate or make poll() return null -- this test
            // controls the exact moment poll() throws, independent of close(), unlike every real
            // MavlinkLink implementation in this module.
        }

        void releaseToThrow() {
            releaseGate.countDown();
        }
    }
}

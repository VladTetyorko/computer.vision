package com.drones.vision.adapter.mavlink;

import com.drones.mavlink.transport.ByteChunk;
import com.drones.mavlink.transport.CarrierKind;
import com.drones.mavlink.transport.LinkDescriptor;
import com.drones.mavlink.transport.LinkId;
import com.drones.mavlink.transport.LinkPeer;
import com.drones.mavlink.transport.LinkRegistry;
import com.drones.mavlink.transport.MavlinkLink;
import com.drones.mavlink.transport.SerialRole;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MavlinkGateway}'s {@link LinkRegistry} implementation (LINK-PAIRING-PLAN.md §3.1/§7) --
 * {@code mavlink-core} itself ships no concrete {@link LinkRegistry}, so this is the one place the
 * interface's contract (register mints no new id, unregister never owns the link, only whole-gateway
 * {@code close()} closes what it holds) is actually exercised. Pure in-memory: a hand-built {@link
 * MavlinkLink} double that never produces data, no sockets -- same style as {@link
 * MavlinkGatewayLinkFailureTest}'s {@code FailingLink}.
 */
class LinkRegistryTest {

    @Test
    void registerReturnsTheLinksOwnIdNeverAMintedOne() {
        MavlinkGateway gateway = new MavlinkGateway(MavlinkSettings.defaults());
        try {
            NoopLink link = new NoopLink("link-a");
            LinkId id = gateway.register(link, descriptor("a"));
            assertEquals(link.id(), id);
        } finally {
            gateway.close();
        }
    }

    @Test
    void unregisterDoesNotCloseTheLink() {
        MavlinkGateway gateway = new MavlinkGateway(MavlinkSettings.defaults());
        NoopLink link = new NoopLink("link-b");
        try {
            LinkId id = gateway.register(link, descriptor("b"));
            gateway.unregister(id);
            assertFalse(link.closed.get(),
                    "unregister must never close the link -- the caller that opened it owns closing it (LinkRegistry contract)");
        } finally {
            gateway.close();
        }
    }

    @Test
    void unregisteringAnUnknownOrAlreadyUnregisteredIdIsANoOp() {
        MavlinkGateway gateway = new MavlinkGateway(MavlinkSettings.defaults());
        try {
            assertDoesNotThrow(() -> gateway.unregister(new LinkId("never-registered")));

            NoopLink link = new NoopLink("link-c");
            LinkId id = gateway.register(link, descriptor("c"));
            gateway.unregister(id);
            assertDoesNotThrow(() -> gateway.unregister(id), "unregistering the same id twice must stay a no-op");
        } finally {
            gateway.close();
        }
    }

    @Test
    void multipleLinksRegisterIndependentlyUnderTheirOwnIds() {
        MavlinkGateway gateway = new MavlinkGateway(MavlinkSettings.defaults());
        try {
            NoopLink linkA = new NoopLink("multi-a");
            NoopLink linkB = new NoopLink("multi-b");
            LinkId idA = gateway.register(linkA, descriptor("a"));
            LinkId idB = gateway.register(linkB, descriptor("b"));

            assertEquals(linkA.id(), idA);
            assertEquals(linkB.id(), idB);
            assertNotEquals(idA, idB);

            gateway.unregister(idA);
            assertFalse(linkA.closed.get());
            assertFalse(linkB.closed.get(), "unregistering one link must not disturb another registered on the same gateway");
        } finally {
            gateway.close();
        }
    }

    @Test
    void closingTheWholeGatewayClosesEveryStillRegisteredLink() {
        MavlinkGateway gateway = new MavlinkGateway(MavlinkSettings.defaults());
        NoopLink linkA = new NoopLink("close-a");
        NoopLink linkB = new NoopLink("close-b");
        gateway.register(linkA, descriptor("a"));
        gateway.register(linkB, descriptor("b"));

        gateway.close();

        assertTrue(linkA.closed.get(), "close() is the one path that owns link lifetime end-to-end (class javadoc)");
        assertTrue(linkB.closed.get());
    }

    @Test
    void closingAnEmptyGatewayWithNoLinksEverRegisteredIsSafe() {
        MavlinkGateway gateway = new MavlinkGateway(MavlinkSettings.defaults());
        assertDoesNotThrow(gateway::close);
        assertTrue(gateway.isClosed());
    }

    private static LinkDescriptor descriptor(String label) {
        return new LinkDescriptor(CarrierKind.SERIAL, SerialRole.NONE, label, 0);
    }

    /** A {@link MavlinkLink} double that never produces data and records whether it was closed. */
    private static final class NoopLink implements MavlinkLink {
        private final LinkId id;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        NoopLink(String label) {
            this.id = new LinkId("noop:" + label);
        }

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
                Thread.sleep(Math.min(timeout.toMillis(), 20));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
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
            closed.set(true);
        }
    }
}

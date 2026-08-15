package com.drones.mavlink.transport;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real loopback UDP: no mocks, matching this module's own testing convention (and
 * {@code adapter-mavlink}'s precedent) of exercising the actual native socket path rather than a
 * double.
 */
class UdpLoopbackTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    @Test
    void listenLinkReceivesWhatATargetLinkSends() throws Exception {
        try (UdpListenLink listener = new UdpListenLink("127.0.0.1", 0)) {
            int port = extractPort(listener.id());
            try (UdpTargetLink sender = new UdpTargetLink("127.0.0.1", port)) {
                byte[] payload = "hello-mavlink".getBytes(StandardCharsets.UTF_8);
                sender.send(payload, 0, payload.length, sender.defaultTarget());

                ByteChunk chunk = listener.poll(TIMEOUT);
                assertNotNullChunk(chunk);
                assertEquals(payload.length, chunk.length());
                assertArrayEquals(payload, chunk.data());
            }
        }
    }

    @Test
    void listenLinkLearnsTheSenderAsItsDefaultTarget() throws Exception {
        try (UdpListenLink listener = new UdpListenLink("127.0.0.1", 0)) {
            assertEquals(LinkPeer.NONE, listener.defaultTarget());
            int port = extractPort(listener.id());
            try (UdpTargetLink sender = new UdpTargetLink("127.0.0.1", port)) {
                byte[] payload = {1, 2, 3};
                sender.send(payload, 0, payload.length, sender.defaultTarget());
                listener.poll(TIMEOUT);

                assertEquals("127.0.0.1", listener.defaultTarget().host());
                assertTrue(listener.defaultTarget().port() > 0);
            }
        }
    }

    @Test
    void pollReturnsNullOnTimeoutWhenNothingArrives() throws Exception {
        try (UdpListenLink listener = new UdpListenLink("127.0.0.1", 0)) {
            assertNull(listener.poll(Duration.ofMillis(50)));
        }
    }

    @Test
    void closeIsIdempotentAndUnblocksAPendingPoll() throws Exception {
        UdpListenLink listener = new UdpListenLink("127.0.0.1", 0);
        listener.close();
        listener.close(); // must not throw
        assertNull(listener.poll(Duration.ofSeconds(1)));
    }

    @Test
    void sendRejectsAnUnaddressableTarget() throws Exception {
        try (UdpTargetLink link = new UdpTargetLink("127.0.0.1", 14_550)) {
            byte[] payload = {1};
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> link.send(payload, 0, payload.length, LinkPeer.NONE));
        }
    }

    @Test
    void preservesMessageBoundariesIsTrueForUdp() throws Exception {
        try (UdpListenLink listener = new UdpListenLink("127.0.0.1", 0);
             UdpTargetLink target = new UdpTargetLink("127.0.0.1", extractPort(listener.id()))) {
            assertTrue(listener.preservesMessageBoundaries());
            assertTrue(target.preservesMessageBoundaries());
        }
    }

    private static void assertNotNullChunk(ByteChunk chunk) {
        org.junit.jupiter.api.Assertions.assertNotNull(chunk, "expected a datagram to have arrived");
    }

    /** {@code LinkId} value is {@code "udp-listen:<host>:<port>"} -- pull the bound port back out of it. */
    private static int extractPort(LinkId id) {
        String value = id.value();
        return Integer.parseInt(value.substring(value.lastIndexOf(':') + 1));
    }
}

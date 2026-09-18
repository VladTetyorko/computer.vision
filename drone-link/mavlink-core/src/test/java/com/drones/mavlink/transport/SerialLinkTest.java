package com.drones.mavlink.transport;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests against the injectable {@link SerialChannel} seam -- no real port, no jSerialComm
 * native library loaded (LINK-PAIRING-PLAN.md §4 L1: "injectable byte channel, no real port").
 * {@link SerialLinkSocatIT} covers the real jSerialComm-backed path over a pty pair.
 */
class SerialLinkTest {

    @Test
    void idIsDerivedFromThePortDescriptor() {
        SerialLink link = new SerialLink(new FakeSerialChannel("/dev/ttyUSB0"));
        assertEquals(new LinkId("serial:/dev/ttyUSB0"), link.id());
    }

    @Test
    void neverPreservesMessageBoundaries() {
        SerialLink link = new SerialLink(new FakeSerialChannel("/dev/ttyUSB0"));
        assertFalse(link.preservesMessageBoundaries());
    }

    @Test
    void defaultTargetIsNoneASerialLinkHasNoAddressablePeer() {
        SerialLink link = new SerialLink(new FakeSerialChannel("/dev/ttyUSB0"));
        assertEquals(LinkPeer.NONE, link.defaultTarget());
    }

    @Test
    void pollReturnsBytesFedThroughTheChannel() throws Exception {
        FakeSerialChannel channel = new FakeSerialChannel("/dev/ttyUSB0");
        SerialLink link = new SerialLink(channel);
        byte[] payload = {1, 2, 3, 4};
        channel.feed(payload);

        ByteChunk chunk = link.poll(Duration.ofMillis(200));
        assertArrayEquals(payload, Arrays.copyOf(chunk.data(), chunk.length()));
        assertEquals(LinkPeer.NONE, chunk.source(), "a serial link has no addressable source peer");
    }

    @Test
    void pollReturnsNullOnATimeoutWithNothingAvailable() throws Exception {
        SerialLink link = new SerialLink(new FakeSerialChannel("/dev/ttyUSB0"));
        assertNull(link.poll(Duration.ofMillis(50)), "jSerialComm's semi-blocking read returns 0, not -1, on timeout");
    }

    @Test
    void sendWritesThroughTheChannelIgnoringTheTargetParameter() throws Exception {
        FakeSerialChannel channel = new FakeSerialChannel("/dev/ttyUSB0");
        SerialLink link = new SerialLink(channel);
        byte[] payload = {9, 8, 7};

        link.send(payload, 0, payload.length, LinkPeer.NONE); // "target" is accepted for interface uniformity only

        assertArrayEquals(payload, channel.written());
    }

    @Test
    void closeIsIdempotentAndClosesTheChannel() {
        FakeSerialChannel channel = new FakeSerialChannel("/dev/ttyUSB0");
        SerialLink link = new SerialLink(channel);

        link.close();
        link.close(); // must not throw

        assertTrue(channel.closed.get());
    }

    @Test
    void pollAfterCloseReturnsNullRatherThanReadingTheChannel() throws Exception {
        FakeSerialChannel channel = new FakeSerialChannel("/dev/ttyUSB0");
        SerialLink link = new SerialLink(channel);
        channel.feed(new byte[] {1});

        link.close();

        assertNull(link.poll(Duration.ofMillis(50)));
    }

    @Test
    void aNegativeReadIsReportedAsAGenuineFailureNotASilentClose() {
        // Unlike TcpClientLink's TCP-FIN semantics, a serial EOF has no peer that "chose" to shut
        // down -- it means the device disappeared (unplugged), so MavlinkSession#onLinkFailure must
        // still fire for it. Silently returning null here (as TcpClientLink does for a FIN) would
        // let a genuine radio disconnection masquerade as ordinary silence.
        FakeSerialChannel channel = new FakeSerialChannel("/dev/ttyUSB0");
        channel.nextRead = -1;
        SerialLink link = new SerialLink(channel);

        assertThrows(IOException.class, () -> link.poll(Duration.ofMillis(50)));
    }

    @Test
    void aGenuineIoExceptionPropagatesUnlessAlreadyClosed() {
        FakeSerialChannel channel = new FakeSerialChannel("/dev/ttyUSB0");
        channel.failNextRead = true;
        SerialLink link = new SerialLink(channel);

        assertThrows(IOException.class, () -> link.poll(Duration.ofMillis(50)));
    }

    /** In-memory {@link SerialChannel} fake -- no native library, no real port. */
    private static final class FakeSerialChannel implements SerialChannel {
        private final String descriptor;
        private final Deque<byte[]> queue = new ArrayDeque<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private byte[] lastWritten = new byte[0];
        int nextRead = 0;
        boolean failNextRead = false;

        FakeSerialChannel(String descriptor) {
            this.descriptor = descriptor;
        }

        void feed(byte[] bytes) {
            queue.add(bytes);
        }

        byte[] written() {
            return lastWritten;
        }

        @Override
        public String portDescriptor() {
            return descriptor;
        }

        @Override
        public int read(byte[] buffer, int timeoutMillis) throws IOException {
            if (failNextRead) {
                failNextRead = false;
                throw new IOException("simulated read failure");
            }
            if (nextRead < 0) {
                return nextRead;
            }
            byte[] next = queue.poll();
            if (next == null) {
                return 0; // semi-blocking timeout: nothing available
            }
            System.arraycopy(next, 0, buffer, 0, next.length);
            return next.length;
        }

        @Override
        public void write(byte[] data, int off, int len) {
            lastWritten = Arrays.copyOfRange(data, off, off + len);
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}

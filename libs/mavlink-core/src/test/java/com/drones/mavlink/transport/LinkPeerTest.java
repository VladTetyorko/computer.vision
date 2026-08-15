package com.drones.mavlink.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LinkPeerTest {

    @Test
    void noneIsBlankHostAndZeroPort() {
        assertEquals("", LinkPeer.NONE.host());
        assertEquals(0, LinkPeer.NONE.port());
    }

    @Test
    void rejectsNegativePort() {
        assertThrows(IllegalArgumentException.class, () -> new LinkPeer("127.0.0.1", -1));
    }

    @Test
    void rejectsPortAboveRange() {
        assertThrows(IllegalArgumentException.class, () -> new LinkPeer("127.0.0.1", 70_000));
    }

    @Test
    void rejectsNullHost() {
        assertThrows(NullPointerException.class, () -> new LinkPeer(null, 1234));
    }
}

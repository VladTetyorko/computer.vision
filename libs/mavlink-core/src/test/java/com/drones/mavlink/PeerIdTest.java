package com.drones.mavlink;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PeerIdTest {

    @Test
    void carriesSystemAndComponent() {
        PeerId peer = new PeerId(new SysId(7), new CompId(1));
        assertEquals(7, peer.system().value());
        assertEquals(1, peer.component().value());
    }

    @Test
    void rejectsNullSystem() {
        assertThrows(NullPointerException.class, () -> new PeerId(null, new CompId(1)));
    }

    @Test
    void rejectsNullComponent() {
        assertThrows(NullPointerException.class, () -> new PeerId(new SysId(1), null));
    }

    @Test
    void equalityIsByValue() {
        assertEquals(new PeerId(new SysId(1), new CompId(1)), new PeerId(new SysId(1), new CompId(1)));
    }
}

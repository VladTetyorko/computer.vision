package com.drones.mavlink.transport;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LinkIntakeTest {

    @Test
    void lastDatagramAtMayBeNullBeforeAnythingHasArrived() {
        LinkIntake intake = new LinkIntake(0, 0, null);
        assertEquals(0, intake.datagramsReceived());
        assertEquals(0, intake.bytesReceived());
        assertNull(intake.lastDatagramAt());
    }

    @Test
    void carriesRealCountsAndATimestamp() {
        Instant now = Instant.now();
        LinkIntake intake = new LinkIntake(412, 51_236, now);
        assertEquals(412, intake.datagramsReceived());
        assertEquals(51_236, intake.bytesReceived());
        assertEquals(now, intake.lastDatagramAt());
    }

    @Test
    void rejectsNegativeDatagramsReceived() {
        assertThrows(IllegalArgumentException.class, () -> new LinkIntake(-1, 0, null));
    }

    @Test
    void rejectsNegativeBytesReceived() {
        assertThrows(IllegalArgumentException.class, () -> new LinkIntake(0, -1, null));
    }
}

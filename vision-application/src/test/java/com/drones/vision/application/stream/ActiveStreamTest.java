package com.drones.vision.application.stream;

import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ActiveStreamTest {

    @Test
    void rejectsAnyMissingComponent() {
        Instant now = Instant.parse("2026-07-22T12:00:00Z");

        assertThrows(IllegalArgumentException.class,
                () -> new ActiveStream(null, DeviceId.random(), now));
        assertThrows(IllegalArgumentException.class,
                () -> new ActiveStream(StreamId.random(), null, now));
        assertThrows(IllegalArgumentException.class,
                () -> new ActiveStream(StreamId.random(), DeviceId.random(), null));
    }
}

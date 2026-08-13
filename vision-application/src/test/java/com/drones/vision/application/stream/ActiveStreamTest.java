package com.drones.vision.application.stream;

import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.StreamId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void threeArgConstructorDefaultsBurnedInToTrue() {
        // docs/plans/active/MEDIA-SOT-PLAN.md §5.4, wave M5: every pre-existing caller was push-mode
        // streaming, the only kind that existed before burnedIn -- "true" is what preserves its reading.
        StreamId streamId = StreamId.random();
        DeviceId deviceId = DeviceId.random();
        Instant now = Instant.parse("2026-07-22T12:00:00Z");

        ActiveStream viaConvenience = new ActiveStream(streamId, deviceId, now);
        ActiveStream viaCanonical = new ActiveStream(streamId, deviceId, now, true);

        assertEquals(viaCanonical, viaConvenience);
        assertTrue(viaConvenience.burnedIn());
    }

    @Test
    void canonicalConstructorAcceptsExplicitBurnedIn() {
        ActiveStream proxied =
                new ActiveStream(StreamId.random(), DeviceId.random(), Instant.parse("2026-07-22T12:00:00Z"), false);

        assertEquals(false, proxied.burnedIn());
    }
}

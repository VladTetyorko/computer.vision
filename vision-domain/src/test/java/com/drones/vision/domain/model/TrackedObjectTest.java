package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;

class TrackedObjectTest {

    private static Detection detection() {
        return new Detection("person", 0.9, new BoundingBox(0, 0, 0.1, 0.1), new ModelRef("yolo", "1"));
    }

    @Test
    void rejectsNegativeTrackId() {
        Instant now = Instant.now();
        assertThrows(IllegalArgumentException.class, () -> new TrackedObject(-1L, detection(), now, now));
    }

    @Test
    void rejectsLastSeenBeforeFirstSeen() {
        Instant first = Instant.now();
        Instant last = first.minusSeconds(1);
        assertThrows(IllegalArgumentException.class, () -> new TrackedObject(1L, detection(), first, last));
    }

    @Test
    void rejectsNullDetectionOrTimestamps() {
        Instant now = Instant.now();
        assertThrows(IllegalArgumentException.class, () -> new TrackedObject(1L, null, now, now));
        assertThrows(IllegalArgumentException.class, () -> new TrackedObject(1L, detection(), null, now));
        assertThrows(IllegalArgumentException.class, () -> new TrackedObject(1L, detection(), now, null));
    }

    @Test
    void acceptsEqualFirstAndLastSeen() {
        Instant now = Instant.now();
        new TrackedObject(1L, detection(), now, now);
    }
}

package com.drones.vision.perception.domain.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DetectionEventIdTest {

    @Test
    void rejectsNullValue() {
        assertThrows(IllegalArgumentException.class, () -> new DetectionEventId(null));
    }

    @Test
    void acceptsNonNullValue() {
        UUID uuid = UUID.randomUUID();

        assertEquals(uuid, new DetectionEventId(uuid).value());
    }

    @Test
    void randomGeneratesDistinctIds() {
        DetectionEventId a = DetectionEventId.random();
        DetectionEventId b = DetectionEventId.random();

        assertNotEquals(a, b);
    }

    @Test
    void ofRoundTripsACanonicalUuidString() {
        UUID uuid = UUID.randomUUID();

        assertEquals(new DetectionEventId(uuid), DetectionEventId.of(uuid.toString()));
    }

    @Test
    void ofRejectsInvalidUuidString() {
        assertThrows(IllegalArgumentException.class, () -> DetectionEventId.of("not-a-uuid"));
        assertThrows(IllegalArgumentException.class, () -> DetectionEventId.of(""));
    }

    @Test
    void ofRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> DetectionEventId.of(null));
    }
}

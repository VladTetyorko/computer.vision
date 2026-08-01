package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrainingSampleIdTest {

    @Test
    void rejectsNullValue() {
        assertThrows(IllegalArgumentException.class, () -> new TrainingSampleId(null));
    }

    @Test
    void acceptsNonNullValue() {
        UUID uuid = UUID.randomUUID();

        assertEquals(uuid, new TrainingSampleId(uuid).value());
    }

    @Test
    void randomGeneratesDistinctIds() {
        TrainingSampleId a = TrainingSampleId.random();
        TrainingSampleId b = TrainingSampleId.random();

        assertNotEquals(a, b);
    }

    @Test
    void ofRoundTripsACanonicalUuidString() {
        UUID uuid = UUID.randomUUID();

        assertEquals(new TrainingSampleId(uuid), TrainingSampleId.of(uuid.toString()));
    }

    @Test
    void ofRejectsInvalidUuidString() {
        assertThrows(IllegalArgumentException.class, () -> TrainingSampleId.of("not-a-uuid"));
        assertThrows(IllegalArgumentException.class, () -> TrainingSampleId.of(""));
    }

    @Test
    void ofRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> TrainingSampleId.of(null));
    }
}

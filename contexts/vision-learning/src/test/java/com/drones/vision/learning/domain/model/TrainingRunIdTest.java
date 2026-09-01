package com.drones.vision.learning.domain.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrainingRunIdTest {

    @Test
    void rejectsNullValue() {
        assertThrows(IllegalArgumentException.class, () -> new TrainingRunId(null));
    }

    @Test
    void acceptsNonNullValue() {
        UUID uuid = UUID.randomUUID();

        assertEquals(uuid, new TrainingRunId(uuid).value());
    }

    @Test
    void randomGeneratesDistinctIds() {
        TrainingRunId a = TrainingRunId.random();
        TrainingRunId b = TrainingRunId.random();

        assertNotEquals(a, b);
    }

    @Test
    void ofRoundTripsACanonicalUuidString() {
        UUID uuid = UUID.randomUUID();

        assertEquals(new TrainingRunId(uuid), TrainingRunId.of(uuid.toString()));
    }

    @Test
    void ofRejectsInvalidUuidString() {
        assertThrows(IllegalArgumentException.class, () -> TrainingRunId.of("not-a-uuid"));
        assertThrows(IllegalArgumentException.class, () -> TrainingRunId.of(""));
    }

    @Test
    void ofRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> TrainingRunId.of(null));
    }
}

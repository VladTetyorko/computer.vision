package com.drones.vision.perception.domain.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FeedIdTest {

    @Test
    void rejectsNullValue() {
        assertThrows(IllegalArgumentException.class, () -> new FeedId(null));
    }

    @Test
    void acceptsNonNullValue() {
        UUID uuid = UUID.randomUUID();

        assertEquals(uuid, new FeedId(uuid).value());
    }

    @Test
    void randomGeneratesDistinctIds() {
        FeedId a = FeedId.random();
        FeedId b = FeedId.random();

        assertNotEquals(a, b);
    }

    @Test
    void ofRoundTripsACanonicalUuidString() {
        UUID uuid = UUID.randomUUID();

        assertEquals(new FeedId(uuid), FeedId.of(uuid.toString()));
    }

    @Test
    void ofRejectsInvalidUuidString() {
        assertThrows(IllegalArgumentException.class, () -> FeedId.of("not-a-uuid"));
        assertThrows(IllegalArgumentException.class, () -> FeedId.of(""));
    }

    @Test
    void ofRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> FeedId.of(null));
    }
}

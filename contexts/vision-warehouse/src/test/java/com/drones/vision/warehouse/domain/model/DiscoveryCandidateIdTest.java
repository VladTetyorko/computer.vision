package com.drones.vision.warehouse.domain.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiscoveryCandidateIdTest {

    @Test
    void rejectsNullValue() {
        assertThrows(IllegalArgumentException.class, () -> new DiscoveryCandidateId(null));
    }

    @Test
    void acceptsNonNullValue() {
        UUID uuid = UUID.randomUUID();

        assertEquals(uuid, new DiscoveryCandidateId(uuid).value());
    }

    @Test
    void randomGeneratesDistinctIds() {
        DiscoveryCandidateId a = DiscoveryCandidateId.random();
        DiscoveryCandidateId b = DiscoveryCandidateId.random();

        assertNotEquals(a, b);
    }

    @Test
    void ofRoundTripsACanonicalUuidString() {
        UUID uuid = UUID.randomUUID();

        assertEquals(new DiscoveryCandidateId(uuid), DiscoveryCandidateId.of(uuid.toString()));
    }

    @Test
    void ofRejectsInvalidUuidString() {
        assertThrows(IllegalArgumentException.class, () -> DiscoveryCandidateId.of("not-a-uuid"));
        assertThrows(IllegalArgumentException.class, () -> DiscoveryCandidateId.of(""));
    }

    @Test
    void ofRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> DiscoveryCandidateId.of(null));
    }
}

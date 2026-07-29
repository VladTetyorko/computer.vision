package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ZoneIdTest {

    @Test
    void rejectsNullValue() {
        assertThrows(IllegalArgumentException.class, () -> new ZoneId(null));
    }

    @Test
    void acceptsNonNullValue() {
        UUID uuid = UUID.randomUUID();

        assertEquals(uuid, new ZoneId(uuid).value());
    }

    @Test
    void randomGeneratesDistinctIds() {
        ZoneId a = ZoneId.random();
        ZoneId b = ZoneId.random();

        assertNotEquals(a, b);
    }

    @Test
    void ofRoundTripsACanonicalUuidString() {
        UUID uuid = UUID.randomUUID();

        assertEquals(new ZoneId(uuid), ZoneId.of(uuid.toString()));
    }

    @Test
    void ofRejectsInvalidUuidString() {
        assertThrows(IllegalArgumentException.class, () -> ZoneId.of("not-a-uuid"));
        assertThrows(IllegalArgumentException.class, () -> ZoneId.of(""));
    }

    @Test
    void ofRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> ZoneId.of(null));
    }
}

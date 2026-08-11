package com.drones.vision.kernel;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UsageIdTest {

    @Test
    void rejectsNullValue() {
        assertThrows(IllegalArgumentException.class, () -> new UsageId(null));
    }

    @Test
    void acceptsNonNullValue() {
        UUID uuid = UUID.randomUUID();

        assertEquals(uuid, new UsageId(uuid).value());
    }

    @Test
    void randomGeneratesDistinctIds() {
        UsageId a = UsageId.random();
        UsageId b = UsageId.random();

        assertNotEquals(a, b);
    }

    @Test
    void ofRoundTripsACanonicalUuidString() {
        UUID uuid = UUID.randomUUID();

        assertEquals(new UsageId(uuid), UsageId.of(uuid.toString()));
    }

    @Test
    void ofRejectsInvalidUuidString() {
        assertThrows(IllegalArgumentException.class, () -> UsageId.of("not-a-uuid"));
        assertThrows(IllegalArgumentException.class, () -> UsageId.of(""));
    }

    @Test
    void ofRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> UsageId.of(null));
    }
}

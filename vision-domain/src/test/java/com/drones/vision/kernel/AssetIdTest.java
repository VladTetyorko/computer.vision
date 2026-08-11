package com.drones.vision.kernel;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssetIdTest {

    @Test
    void rejectsNullValue() {
        assertThrows(IllegalArgumentException.class, () -> new AssetId(null));
    }

    @Test
    void acceptsNonNullValue() {
        UUID uuid = UUID.randomUUID();

        assertEquals(uuid, new AssetId(uuid).value());
    }

    @Test
    void randomGeneratesDistinctIds() {
        AssetId a = AssetId.random();
        AssetId b = AssetId.random();

        assertNotEquals(a, b);
    }

    @Test
    void ofRoundTripsACanonicalUuidString() {
        UUID uuid = UUID.randomUUID();

        assertEquals(new AssetId(uuid), AssetId.of(uuid.toString()));
    }

    @Test
    void ofRejectsInvalidUuidString() {
        assertThrows(IllegalArgumentException.class, () -> AssetId.of("not-a-uuid"));
        assertThrows(IllegalArgumentException.class, () -> AssetId.of(""));
    }

    @Test
    void ofRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> AssetId.of(null));
    }
}

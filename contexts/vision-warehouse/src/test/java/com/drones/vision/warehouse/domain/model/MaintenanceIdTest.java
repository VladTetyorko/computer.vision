package com.drones.vision.warehouse.domain.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MaintenanceIdTest {

    @Test
    void rejectsNullValue() {
        assertThrows(IllegalArgumentException.class, () -> new MaintenanceId(null));
    }

    @Test
    void acceptsNonNullValue() {
        UUID uuid = UUID.randomUUID();

        assertEquals(uuid, new MaintenanceId(uuid).value());
    }

    @Test
    void randomGeneratesDistinctIds() {
        MaintenanceId a = MaintenanceId.random();
        MaintenanceId b = MaintenanceId.random();

        assertNotEquals(a, b);
    }

    @Test
    void ofRoundTripsACanonicalUuidString() {
        UUID uuid = UUID.randomUUID();

        assertEquals(new MaintenanceId(uuid), MaintenanceId.of(uuid.toString()));
    }

    @Test
    void ofRejectsInvalidUuidString() {
        assertThrows(IllegalArgumentException.class, () -> MaintenanceId.of("not-a-uuid"));
        assertThrows(IllegalArgumentException.class, () -> MaintenanceId.of(""));
    }

    @Test
    void ofRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> MaintenanceId.of(null));
    }
}

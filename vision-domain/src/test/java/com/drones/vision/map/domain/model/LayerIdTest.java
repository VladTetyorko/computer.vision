package com.drones.vision.map.domain.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LayerIdTest {

    @Test
    void rejectsNullValue() {
        assertThrows(IllegalArgumentException.class, () -> new LayerId(null));
    }

    @Test
    void acceptsNonNullValue() {
        UUID uuid = UUID.randomUUID();

        assertEquals(uuid, new LayerId(uuid).value());
    }

    @Test
    void randomGeneratesDistinctIds() {
        LayerId a = LayerId.random();
        LayerId b = LayerId.random();

        assertNotEquals(a, b);
    }

    @Test
    void ofRoundTripsACanonicalUuidString() {
        UUID uuid = UUID.randomUUID();

        assertEquals(new LayerId(uuid), LayerId.of(uuid.toString()));
    }

    @Test
    void ofRejectsInvalidUuidString() {
        assertThrows(IllegalArgumentException.class, () -> LayerId.of("not-a-uuid"));
        assertThrows(IllegalArgumentException.class, () -> LayerId.of(""));
    }

    @Test
    void ofRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> LayerId.of(null));
    }
}

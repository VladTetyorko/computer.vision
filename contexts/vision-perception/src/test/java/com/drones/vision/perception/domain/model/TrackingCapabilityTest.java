package com.drones.vision.perception.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrackingCapabilityTest {

    @Test
    void rejectsLevelServedBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> new TrackingCapability(0, ""));
        assertThrows(IllegalArgumentException.class, () -> new TrackingCapability(-1, ""));
    }

    @Test
    void rejectsLevelServedAboveFive() {
        assertThrows(IllegalArgumentException.class, () -> new TrackingCapability(6, ""));
    }

    @Test
    void acceptsBoundaryLevelServed() {
        assertDoesNotThrow(() -> new TrackingCapability(1, ""));
        assertDoesNotThrow(() -> new TrackingCapability(5, ""));
    }

    @Test
    void rejectsNullReason() {
        assertThrows(IllegalArgumentException.class, () -> new TrackingCapability(3, null));
    }

    @Test
    void emptyReasonMeansServedAsRequested() {
        TrackingCapability capability = new TrackingCapability(3, "");

        assertEquals("", capability.reason());
    }

    @Test
    void acceptsANonEmptyDowngradeReason() {
        TrackingCapability capability = new TrackingCapability(2, "host affords L2 only");

        assertEquals(2, capability.levelServed());
        assertEquals("host affords L2 only", capability.reason());
    }
}

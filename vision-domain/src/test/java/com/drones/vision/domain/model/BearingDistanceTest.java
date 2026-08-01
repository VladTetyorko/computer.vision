package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BearingDistanceTest {

    @Test
    void acceptsBearingAtLowerBoundZero() {
        BearingDistance bd = new BearingDistance(0.0, 100.0);

        assertEquals(0.0, bd.bearingDegrees());
    }

    @Test
    void rejectsBearingAtOrAboveThreeSixty() {
        assertThrows(IllegalArgumentException.class, () -> new BearingDistance(360.0, 100.0));
        assertThrows(IllegalArgumentException.class, () -> new BearingDistance(400.0, 100.0));
    }

    @Test
    void rejectsNegativeBearing() {
        assertThrows(IllegalArgumentException.class, () -> new BearingDistance(-1.0, 100.0));
    }

    @Test
    void rejectsNegativeDistance() {
        assertThrows(IllegalArgumentException.class, () -> new BearingDistance(90.0, -1.0));
    }

    @Test
    void acceptsZeroDistance() {
        BearingDistance bd = new BearingDistance(90.0, 0.0);

        assertEquals(0.0, bd.distanceMeters());
    }
}

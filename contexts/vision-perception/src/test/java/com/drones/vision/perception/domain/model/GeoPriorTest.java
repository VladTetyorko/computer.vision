package com.drones.vision.perception.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class GeoPriorTest {

    @Test
    void rejectsNonPositiveRadius() {
        assertThrows(IllegalArgumentException.class, () -> new GeoPrior(50.0, 30.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new GeoPrior(50.0, 30.0, -1.0));
    }

    @Test
    void rejectsOutOfRangeLatitudeAndLongitude() {
        assertThrows(IllegalArgumentException.class, () -> new GeoPrior(90.1, 30.0, 100.0));
        assertThrows(IllegalArgumentException.class, () -> new GeoPrior(50.0, 180.1, 100.0));
    }
}

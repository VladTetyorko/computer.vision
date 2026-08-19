package com.drones.vision.perception.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RegionBoundsTest {

    @Test
    void storesEveryField() {
        RegionBounds bounds = new RegionBounds(50.4020, 50.3860, 30.6400, 30.6120);

        assertEquals(50.4020, bounds.north());
        assertEquals(50.3860, bounds.south());
        assertEquals(30.6400, bounds.east());
        assertEquals(30.6120, bounds.west());
    }

    @Test
    void rejectsSouthNotStrictlyLessThanNorth() {
        assertThrows(IllegalArgumentException.class, () -> new RegionBounds(50.0, 50.0, 30.0, 29.0));
        assertThrows(IllegalArgumentException.class, () -> new RegionBounds(50.0, 51.0, 30.0, 29.0));
    }

    @Test
    void rejectsWestNotStrictlyLessThanEast() {
        assertThrows(IllegalArgumentException.class, () -> new RegionBounds(50.0, 49.0, 30.0, 30.0));
        assertThrows(IllegalArgumentException.class, () -> new RegionBounds(50.0, 49.0, 30.0, 31.0));
    }

    @Test
    void rejectsOutOfRangeLatitudeAndLongitude() {
        assertThrows(IllegalArgumentException.class, () -> new RegionBounds(90.1, 0.0, 1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new RegionBounds(1.0, -90.1, 1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new RegionBounds(1.0, 0.0, 180.1, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new RegionBounds(1.0, 0.0, 1.0, -180.1));
    }
}

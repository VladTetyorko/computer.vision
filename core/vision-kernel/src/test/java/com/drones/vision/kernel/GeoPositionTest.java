package com.drones.vision.kernel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GeoPositionTest {

    @Test
    void acceptsBoundaryValues() {
        assertEquals(90.0, new GeoPosition(90.0, 180.0, null).latitude());
        assertEquals(-90.0, new GeoPosition(-90.0, -180.0, null).latitude());
        assertEquals(180.0, new GeoPosition(0.0, 180.0, null).longitude());
        assertEquals(-180.0, new GeoPosition(0.0, -180.0, null).longitude());
    }

    @Test
    void altitudeIsNullable() {
        assertNull(new GeoPosition(0.0, 0.0, null).altitudeMeters());
        assertEquals(120.5, new GeoPosition(0.0, 0.0, 120.5).altitudeMeters());
    }

    @Test
    void rejectsOutOfRangeLatitude() {
        assertThrows(IllegalArgumentException.class, () -> new GeoPosition(90.1, 0.0, null));
        assertThrows(IllegalArgumentException.class, () -> new GeoPosition(-90.1, 0.0, null));
        assertThrows(IllegalArgumentException.class, () -> new GeoPosition(Double.NaN, 0.0, null));
    }

    @Test
    void rejectsOutOfRangeLongitude() {
        assertThrows(IllegalArgumentException.class, () -> new GeoPosition(0.0, 180.1, null));
        assertThrows(IllegalArgumentException.class, () -> new GeoPosition(0.0, -180.1, null));
        assertThrows(IllegalArgumentException.class, () -> new GeoPosition(0.0, Double.NaN, null));
    }
}

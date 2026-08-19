package com.drones.vision.kernel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GroundFixTest {

    private static final GeoPosition SOME_POSITION = new GeoPosition(50.45, 30.52, null);

    @Test
    void storesPositionRangeAndErrorRadius() {
        GroundFix fix = new GroundFix(SOME_POSITION, 84.2, 6.8);

        assertEquals(SOME_POSITION, fix.position());
        assertEquals(84.2, fix.rangeMeters());
        assertEquals(6.8, fix.errorRadiusMeters());
    }

    @Test
    void acceptsZeroRangeAndZeroErrorRadius() {
        GroundFix fix = new GroundFix(SOME_POSITION, 0.0, 0.0);

        assertEquals(0.0, fix.rangeMeters());
        assertEquals(0.0, fix.errorRadiusMeters());
    }

    @Test
    void rejectsNullPosition() {
        assertThrows(IllegalArgumentException.class, () -> new GroundFix(null, 1.0, 1.0));
    }

    @Test
    void rejectsNegativeRange() {
        assertThrows(IllegalArgumentException.class, () -> new GroundFix(SOME_POSITION, -1.0, 1.0));
    }

    @Test
    void rejectsNegativeErrorRadius() {
        assertThrows(IllegalArgumentException.class, () -> new GroundFix(SOME_POSITION, 1.0, -1.0));
    }
}

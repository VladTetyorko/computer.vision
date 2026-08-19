package com.drones.vision.kernel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FixedCameraGeoSettingsTest {

    @Test
    void storesAllFourLimits() {
        FixedCameraGeoSettings settings = new FixedCameraGeoSettings(1.0, 500.0, 0.5, 100.0);

        assertEquals(1.0, settings.minRayDepressionDegrees());
        assertEquals(500.0, settings.maxRangeMeters());
        assertEquals(0.5, settings.angularErrorDegrees());
        assertEquals(100.0, settings.maxErrorRadiusMeters());
    }

    @Test
    void acceptsGuardOfExactlyNinety() {
        FixedCameraGeoSettings settings = new FixedCameraGeoSettings(90.0, 500.0, 0.5, 100.0);

        assertEquals(90.0, settings.minRayDepressionDegrees());
    }

    @Test
    void rejectsGuardOutsideZeroToNinety() {
        assertThrows(IllegalArgumentException.class,
                () -> new FixedCameraGeoSettings(0.0, 500.0, 0.5, 100.0));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedCameraGeoSettings(90.1, 500.0, 0.5, 100.0));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedCameraGeoSettings(-1.0, 500.0, 0.5, 100.0));
    }

    @Test
    void rejectsNonPositiveMaxRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new FixedCameraGeoSettings(1.0, 0.0, 0.5, 100.0));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedCameraGeoSettings(1.0, Double.POSITIVE_INFINITY, 0.5, 100.0));
    }

    @Test
    void rejectsNonPositiveAngularError() {
        assertThrows(IllegalArgumentException.class,
                () -> new FixedCameraGeoSettings(1.0, 500.0, 0.0, 100.0));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedCameraGeoSettings(1.0, 500.0, -0.5, 100.0));
    }

    @Test
    void rejectsNonPositiveMaxErrorRadius() {
        assertThrows(IllegalArgumentException.class,
                () -> new FixedCameraGeoSettings(1.0, 500.0, 0.5, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedCameraGeoSettings(1.0, 500.0, 0.5, Double.NaN));
    }
}

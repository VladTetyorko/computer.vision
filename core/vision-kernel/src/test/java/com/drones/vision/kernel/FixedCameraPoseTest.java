package com.drones.vision.kernel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FixedCameraPoseTest {

    private static final GeoPosition SOME_POSITION = new GeoPosition(50.45, 30.52, null);

    @Test
    void storesAllFiveGeometricValues() {
        FixedCameraPose pose = new FixedCameraPose(SOME_POSITION, 12.0, 214.0, 8.5, 62.0);

        assertEquals(SOME_POSITION, pose.position());
        assertEquals(12.0, pose.aglMeters());
        assertEquals(214.0, pose.yawDegrees());
        assertEquals(8.5, pose.pitchDegrees());
        assertEquals(62.0, pose.hfovDegrees());
    }

    @Test
    void rejectsNullPosition() {
        assertThrows(IllegalArgumentException.class, () -> new FixedCameraPose(null, 12.0, 0.0, 0.0, 60.0));
    }

    @Test
    void rejectsNegativeAgl() {
        assertThrows(IllegalArgumentException.class,
                () -> new FixedCameraPose(SOME_POSITION, -1.0, 0.0, 0.0, 60.0));
    }

    @Test
    void rejectsNonFiniteYaw() {
        assertThrows(IllegalArgumentException.class,
                () -> new FixedCameraPose(SOME_POSITION, 12.0, Double.NaN, 0.0, 60.0));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedCameraPose(SOME_POSITION, 12.0, Double.POSITIVE_INFINITY, 0.0, 60.0));
    }

    @Test
    void rejectsNonFinitePitch() {
        assertThrows(IllegalArgumentException.class,
                () -> new FixedCameraPose(SOME_POSITION, 12.0, 0.0, Double.NaN, 60.0));
    }

    @Test
    void acceptsUpwardTiltingPitch() {
        // A fixed camera mounted with a slight upward tilt is legal geometry here; individual rays
        // still face the horizon guard in FixedCameraGeo.
        FixedCameraPose pose = new FixedCameraPose(SOME_POSITION, 12.0, 0.0, -5.0, 60.0);

        assertEquals(-5.0, pose.pitchDegrees());
    }

    @Test
    void rejectsHfovOutsideOpenZeroToOneEighty() {
        assertThrows(IllegalArgumentException.class,
                () -> new FixedCameraPose(SOME_POSITION, 12.0, 0.0, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedCameraPose(SOME_POSITION, 12.0, 0.0, 0.0, -10.0));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedCameraPose(SOME_POSITION, 12.0, 0.0, 0.0, 180.0));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedCameraPose(SOME_POSITION, 12.0, 0.0, 0.0, Double.NaN));
    }
}

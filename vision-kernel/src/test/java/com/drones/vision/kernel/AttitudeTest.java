package com.drones.vision.kernel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttitudeTest {

    @Test
    void allNullFieldsAreAllowed() {
        Attitude attitude = new Attitude(null, null, null, null, null, null);

        assertFalse(attitude.hasAircraftAttitude());
        assertFalse(attitude.hasGimbal());
    }

    @Test
    void acceptsFiniteValuesForEveryField() {
        Attitude attitude = new Attitude(1.0, -2.0, 3.0, -4.0, 5.0, -6.0);

        assertEquals(1.0, attitude.rollDegrees());
        assertEquals(-2.0, attitude.pitchDegrees());
        assertEquals(3.0, attitude.yawDegrees());
        assertEquals(-4.0, attitude.gimbalRollDegrees());
        assertEquals(5.0, attitude.gimbalPitchDegrees());
        assertEquals(-6.0, attitude.gimbalYawDegrees());
    }

    @Test
    void rejectsNonFiniteRollDegrees() {
        assertThrows(IllegalArgumentException.class,
                () -> new Attitude(Double.NaN, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Attitude(Double.POSITIVE_INFINITY, null, null, null, null, null));
    }

    @Test
    void rejectsNonFinitePitchDegrees() {
        assertThrows(IllegalArgumentException.class,
                () -> new Attitude(null, Double.NaN, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Attitude(null, Double.NEGATIVE_INFINITY, null, null, null, null));
    }

    @Test
    void rejectsNonFiniteYawDegrees() {
        assertThrows(IllegalArgumentException.class,
                () -> new Attitude(null, null, Double.NaN, null, null, null));
    }

    @Test
    void rejectsNonFiniteGimbalRollDegrees() {
        assertThrows(IllegalArgumentException.class,
                () -> new Attitude(null, null, null, Double.NaN, null, null));
    }

    @Test
    void rejectsNonFiniteGimbalPitchDegrees() {
        assertThrows(IllegalArgumentException.class,
                () -> new Attitude(null, null, null, null, Double.NaN, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Attitude(null, null, null, null, Double.POSITIVE_INFINITY, null));
    }

    @Test
    void rejectsNonFiniteGimbalYawDegrees() {
        assertThrows(IllegalArgumentException.class,
                () -> new Attitude(null, null, null, null, null, Double.NaN));
    }

    @Test
    void hasGimbalIsTrueWhenAnySingleGimbalComponentIsPresent() {
        assertTrue(new Attitude(null, null, null, 1.0, null, null).hasGimbal());
        assertTrue(new Attitude(null, null, null, null, 1.0, null).hasGimbal());
        assertTrue(new Attitude(null, null, null, null, null, 1.0).hasGimbal());
    }

    @Test
    void hasGimbalIsFalseWhenOnlyAircraftAttitudeIsPresent() {
        Attitude attitude = new Attitude(1.0, 2.0, 3.0, null, null, null);

        assertFalse(attitude.hasGimbal());
        assertTrue(attitude.hasAircraftAttitude());
    }

    @Test
    void hasAircraftAttitudeIsTrueWhenAnySingleAircraftComponentIsPresent() {
        assertTrue(new Attitude(1.0, null, null, null, null, null).hasAircraftAttitude());
        assertTrue(new Attitude(null, 1.0, null, null, null, null).hasAircraftAttitude());
        assertTrue(new Attitude(null, null, 1.0, null, null, null).hasAircraftAttitude());
    }
}

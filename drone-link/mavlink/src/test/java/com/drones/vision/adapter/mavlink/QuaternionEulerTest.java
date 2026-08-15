package com.drones.vision.adapter.mavlink;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Hand-computed cross-checks for {@link QuaternionEuler#fromQuaternion}, the one piece of genuinely
 * new math in docs/plans/active/GEO-POSE-PLAN.md wave V2 (the parked {@code feat/visual-geo} branch
 * flagged its own quaternion path as "not hardware/SITL-validated" -- this class exists so this one
 * isn't). Every input quaternion below is built from a known roll/pitch/yaw triple via the standard
 * ZYX quaternion-<em>from</em>-Euler construction ({@code q = qz(yaw)&middot;qy(pitch)&middot;qx(roll)},
 * the textbook inverse of the formula under test) computed inline with plain trig calls -- not
 * copied from any generated or precomputed source -- so a reader can verify each one by hand from the
 * half-angle values alone.
 */
class QuaternionEulerTest {

    private static final double DELTA_DEGREES = 1e-9;

    @Test
    void identityQuaternionIsAllZeroAngles() {
        QuaternionEuler.Euler euler = QuaternionEuler.fromQuaternion(1, 0, 0, 0);

        assertEquals(0.0, euler.rollDegrees(), DELTA_DEGREES);
        assertEquals(0.0, euler.pitchDegrees(), DELTA_DEGREES);
        assertEquals(0.0, euler.yawDegrees(), DELTA_DEGREES);
    }

    @Test
    void pureNinetyDegreePitchDown() {
        // Rotation of -90 deg about the pitch (Y) axis only: q = (cos(-45deg), 0, sin(-45deg), 0).
        double halfAngleRadians = Math.toRadians(-90.0) / 2.0;
        QuaternionEuler.Euler euler = QuaternionEuler.fromQuaternion(
                Math.cos(halfAngleRadians), 0.0, Math.sin(halfAngleRadians), 0.0);

        // This sits exactly at the asin(-1) gimbal-lock pole: roll/yaw are coupled and the
        // decomposition conventionally reports them both as zero, with the whole rotation folded
        // into pitch -- see QuaternionEuler's own javadoc.
        assertEquals(0.0, euler.rollDegrees(), DELTA_DEGREES);
        assertEquals(-90.0, euler.pitchDegrees(), DELTA_DEGREES);
        assertEquals(0.0, euler.yawDegrees(), DELTA_DEGREES);
    }

    @Test
    void pureNinetyDegreeYaw() {
        // Rotation of +90 deg about the yaw (Z) axis only: q = (cos(45deg), 0, 0, sin(45deg)).
        double halfAngleRadians = Math.toRadians(90.0) / 2.0;
        QuaternionEuler.Euler euler = QuaternionEuler.fromQuaternion(
                Math.cos(halfAngleRadians), 0.0, 0.0, Math.sin(halfAngleRadians));

        assertEquals(0.0, euler.rollDegrees(), DELTA_DEGREES);
        assertEquals(0.0, euler.pitchDegrees(), DELTA_DEGREES);
        assertEquals(90.0, euler.yawDegrees(), DELTA_DEGREES);
    }

    @Test
    void combinedRollPitchYawRoundTripsThroughTheStandardForwardFormula() {
        double rollDegrees = 30.0;
        double pitchDegrees = 20.0;
        double yawDegrees = 40.0;

        double r = Math.toRadians(rollDegrees) / 2.0;
        double p = Math.toRadians(pitchDegrees) / 2.0;
        double y = Math.toRadians(yawDegrees) / 2.0;
        double cr = Math.cos(r);
        double sr = Math.sin(r);
        double cp = Math.cos(p);
        double sp = Math.sin(p);
        double cy = Math.cos(y);
        double sy = Math.sin(y);

        // Standard ZYX quaternion-from-Euler construction (q = qz(yaw)*qy(pitch)*qx(roll)), the
        // textbook forward formula QuaternionEuler.fromQuaternion inverts.
        double w = cr * cp * cy + sr * sp * sy;
        double x = sr * cp * cy - cr * sp * sy;
        double qy = cr * sp * cy + sr * cp * sy;
        double qz = cr * cp * sy - sr * sp * cy;

        QuaternionEuler.Euler euler = QuaternionEuler.fromQuaternion(w, x, qy, qz);

        assertEquals(rollDegrees, euler.rollDegrees(), DELTA_DEGREES);
        assertEquals(pitchDegrees, euler.pitchDegrees(), DELTA_DEGREES);
        assertEquals(yawDegrees, euler.yawDegrees(), DELTA_DEGREES);
    }

    @Test
    void nonUnitQuaternionNeverProducesNaN() {
        // A malformed/un-normalized wire value must still decode to a finite (if physically
        // meaningless) result rather than blow up the decoder -- see the class javadoc.
        QuaternionEuler.Euler euler = QuaternionEuler.fromQuaternion(0.0, 0.0, 0.0, 0.0);

        assertEquals(0.0, euler.rollDegrees(), DELTA_DEGREES);
        assertEquals(0.0, euler.pitchDegrees(), DELTA_DEGREES);
        assertEquals(0.0, euler.yawDegrees(), DELTA_DEGREES);
    }
}

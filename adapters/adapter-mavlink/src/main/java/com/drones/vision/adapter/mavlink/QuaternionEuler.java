package com.drones.vision.adapter.mavlink;

/**
 * Converts a MAVLink quaternion ({@code w, x, y, z} -- "1 0 0 0 is the null-rotation", per {@code
 * GIMBAL_DEVICE_ATTITUDE_STATUS}'s (#285) own field doc) into roll/pitch/yaw Euler angles in the
 * same "aeronautical frame (right-handed, Z-down, Y-right, X-front, ZYX, intrinsic)" that MAVLink's
 * {@code ATTITUDE} (#30) message documents for itself (docs/plans/active/GEO-POSE-PLAN.md §4.2,
 * wave V2) -- the standard aerospace Tait-Bryan ZYX decomposition (yaw about Z applied
 * first/outermost, then pitch about the once-rotated Y, then roll about the twice-rotated X). This
 * is the textbook inverse of the equally standard ZYX quaternion-<em>from</em>-Euler construction
 * ({@code q = qz(yaw)&middot;qy(pitch)&middot;qx(roll)}); the two are algebraic inverses of one
 * another, which is how this class's own unit tests cross-check hand-computed values (build a
 * quaternion from a known Euler triple via the forward formula, then assert this class recovers the
 * same triple).
 *
 * <p>Sign conventions match {@code GIMBAL_DEVICE_ATTITUDE_STATUS}'s own field docs for its angular
 * velocities (positive roll = rolling right, positive pitch = pitching up, positive yaw = yawing
 * right, i.e. clockwise seen from above) and {@link com.drones.vision.kernel.Attitude}'s documented
 * "aircraft-pitch sign convention" (positive up, negative down) -- both verified directly by this
 * class's own pure-pitch and pure-yaw test cases.
 *
 * <p><b>Gimbal lock.</b> At pitch = &plusmn;90&deg; exactly, roll and yaw become coupled (the
 * decomposition is not unique at that pole); this returns {@code roll = 0} with the whole rotation
 * folded into {@code yaw} (or vice versa), the conventional Euler-angle behavior at a singularity,
 * not a bug. {@code Math.atan2(0, 0)} is well-defined as {@code 0} in Java, never {@code NaN}, so no
 * extra branching is needed there. The one place floating-point rounding could otherwise produce a
 * {@code NaN} is {@code asin}'s argument drifting fractionally outside {@code [-1,1]} for a
 * near-vertical pitch (e.g. two 45&deg; half-angle sines multiplying out to {@code 1.0000000001});
 * that argument is clamped before the call.
 */
final class QuaternionEuler {

    private QuaternionEuler() {
    }

    /**
     * @param rollDegrees  rotation about X (body-forward axis), positive = rolling right, degrees
     * @param pitchDegrees rotation about Y (body-right axis), positive = pitching up, degrees
     * @param yawDegrees   rotation about Z (body-down axis), positive = yawing right (clockwise
     *                     seen from above), degrees
     */
    record Euler(double rollDegrees, double pitchDegrees, double yawDegrees) {
    }

    /**
     * @param w scalar quaternion component
     * @param x i (roll-axis) component
     * @param y j (pitch-axis) component
     * @param z k (yaw-axis) component
     * @return the equivalent roll/pitch/yaw, degrees. Never throws and never returns a {@code NaN}
     *         component for any finite input, including a non-unit or all-zero quaternion (every
     *         formula here is either a ratio fed to {@code atan2} or a once-clamped {@code asin},
     *         neither of which divides by the quaternion's own norm) -- a caller with a genuinely
     *         {@code NaN}/infinite wire component must guard before calling, since {@code NaN} in
     *         propagates to {@code NaN} out
     */
    static Euler fromQuaternion(double w, double x, double y, double z) {
        double roll = Math.atan2(2 * (w * x + y * z), 1 - 2 * (x * x + y * y));

        double sinPitch = Math.max(-1.0, Math.min(1.0, 2 * (w * y - z * x)));
        double pitch = Math.asin(sinPitch);

        double yaw = Math.atan2(2 * (w * z + x * y), 1 - 2 * (y * y + z * z));

        return new Euler(Math.toDegrees(roll), Math.toDegrees(pitch), Math.toDegrees(yaw));
    }
}

package com.drones.vision.kernel;

/**
 * A drone's own attitude (roll/pitch/yaw) and, independently, its camera gimbal's orientation
 * (docs/plans/done/GEO-POSE-PLAN.md §4.1) — decoded from MAVLink {@code ATTITUDE} (#30) for the
 * airframe and {@code GIMBAL_DEVICE_ATTITUDE_STATUS} (#285, preferred) or {@code MOUNT_ORIENTATION}
 * (#265, deprecated fallback) for the gimbal.
 *
 * <p>All six components are individually nullable and independent of one another: a fixed camera
 * (no gimbal) reports the {@code gimbal*Degrees} trio as {@code null} forever; a device with no IMU
 * (or a decoder that never wires up {@code ATTITUDE}) reports the aircraft trio as {@code null}
 * forever. Degrees throughout — the MAVLink wire carries radians (aircraft attitude) or a quaternion
 * (gimbal, message #285), and both conversions belong in the adapter decoding the message, not here.
 *
 * <p>Gimbal angles are earth-frame (absolute), not relative to the airframe: {@code
 * gimbalYawDegrees} is the compass direction the camera is pointed, usable directly as a projection
 * bearing without combining it with the aircraft's own heading. {@code gimbalPitchDegrees} follows
 * the same sign convention as aircraft pitch — positive is up, negative is down — which is why
 * {@link GeoProjection#aimFrom} negates it to obtain a camera depression angle.
 *
 * @param rollDegrees        aircraft roll, degrees, nullable
 * @param pitchDegrees       aircraft pitch, degrees, nullable
 * @param yawDegrees         aircraft yaw, degrees, nullable
 * @param gimbalRollDegrees  gimbal roll, earth-frame, degrees, nullable
 * @param gimbalPitchDegrees gimbal pitch, earth-frame, degrees; positive is up, negative is down;
 *                           nullable
 * @param gimbalYawDegrees   gimbal yaw, earth-frame (the compass heading the camera points along),
 *                           degrees; nullable
 */
public record Attitude(Double rollDegrees, Double pitchDegrees, Double yawDegrees,
                        Double gimbalRollDegrees, Double gimbalPitchDegrees, Double gimbalYawDegrees) {

    public Attitude {
        requireFiniteIfPresent("rollDegrees", rollDegrees);
        requireFiniteIfPresent("pitchDegrees", pitchDegrees);
        requireFiniteIfPresent("yawDegrees", yawDegrees);
        requireFiniteIfPresent("gimbalRollDegrees", gimbalRollDegrees);
        requireFiniteIfPresent("gimbalPitchDegrees", gimbalPitchDegrees);
        requireFiniteIfPresent("gimbalYawDegrees", gimbalYawDegrees);
    }

    private static void requireFiniteIfPresent(String fieldName, Double value) {
        if (value != null && (Double.isNaN(value) || Double.isInfinite(value))) {
            throw new IllegalArgumentException("Attitude " + fieldName + " must be finite: " + value);
        }
    }

    /** @return {@code true} if any gimbal orientation component is known (the device reports a gimbal) */
    public boolean hasGimbal() {
        return gimbalRollDegrees != null || gimbalPitchDegrees != null || gimbalYawDegrees != null;
    }

    /** @return {@code true} if any aircraft attitude component is known */
    public boolean hasAircraftAttitude() {
        return rollDegrees != null || pitchDegrees != null || yawDegrees != null;
    }
}

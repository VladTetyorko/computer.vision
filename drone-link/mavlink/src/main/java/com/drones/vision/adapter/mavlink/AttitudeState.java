package com.drones.vision.adapter.mavlink;

import com.drones.vision.kernel.Attitude;

import io.dronefleet.mavlink.common.GimbalDeviceAttitudeStatus;
import io.dronefleet.mavlink.common.GimbalDeviceFlags;
import io.dronefleet.mavlink.common.MountOrientation;
import io.dronefleet.mavlink.util.EnumValue;

import java.util.List;

/**
 * {@link MavlinkTelemetryDecoder}'s accumulated aircraft-attitude / gimbal-orientation fields --
 * {@code ATTITUDE} (#30), {@code GIMBAL_DEVICE_ATTITUDE_STATUS} (#285, preferred), {@code
 * MOUNT_ORIENTATION} (#265, deprecated fallback) -- the newest addition to {@link
 * MavlinkTelemetryDecoder}'s three-way state split (docs/plans/done/GEO-POSE-PLAN.md wave V2). A
 * fourth holder rather than folding into an existing one, matching {@link PositionAndPowerState}'s
 * own javadoc rationale: attitude/gimbal orientation is a new domain concern, not a variant of
 * position/power, flight-controller, or ardupilotmega-extras state. See {@code
 * MavlinkTelemetryDecoder}'s own javadoc for the full unit-conversion table.
 *
 * <h2>#285 preferred over #265, permanently, once seen (G4)</h2>
 * {@link #preferredGimbalSourceSeen} latches {@code true} the first time a {@code
 * GIMBAL_DEVICE_ATTITUDE_STATUS} is applied and never resets; {@link #applyMountOrientation} becomes
 * a no-op from that point on for the remainder of this decoder's lifetime (which is itself one
 * physical vehicle's lifetime, per {@link MavlinkTelemetryDecoder}'s own "fresh decoder per claim"
 * discipline). A device that only ever sends #265 keeps using it normally.
 *
 * <h2>Gimbal yaw is only ever recorded earth-frame</h2>
 * {@link Attitude#gimbalYawDegrees()} is documented earth-frame absolute (kernel wave V1) and {@code
 * GeoProjection.aimFrom} uses it directly as a projection bearing -- reporting a vehicle-relative
 * yaw as if it were earth-frame would silently misdirect a projected mark by however far the
 * aircraft's own heading differs from true north, exactly the class of bug this wave exists to
 * remove. Both source messages can report either frame:
 * <ul>
 *   <li><b>#285</b> -- its own {@code flags} field says which frame the quaternion's yaw is in
 *       ({@code GIMBAL_DEVICE_FLAGS_YAW_IN_EARTH_FRAME} / {@code _YAW_IN_VEHICLE_FRAME}, or neither
 *       set, meaning "backwards-compatibility": earth-frame if {@code GIMBAL_DEVICE_FLAGS_YAW_LOCK}
 *       is also set, else vehicle-frame). When it is vehicle-frame, the message's own {@code
 *       delta_yaw} extension field (radians, {@code NaN} if unknown) is the spec-defined correction
 *       -- {@code q_earth = q_delta_yaw * q_vehicle}, which (being a pure yaw-axis rotation composed
 *       onto the outermost/Z term of a ZYX decomposition) algebraically reduces to simply adding
 *       {@code delta_yaw} to the decoded yaw, leaving roll/pitch untouched. When {@code delta_yaw}
 *       is itself {@code NaN}/infinite (an old #285 sender, or the "neither flag set" backwards-
 *       compatibility case, where the spec says {@code delta_yaw} must be <em>ignored</em> even if
 *       populated), there is no way to convert and gimbal yaw is left {@code null} for that sample
 *       rather than guessed at.</li>
 *   <li><b>#265</b> does not need that trick: its {@code yaw_absolute} extension field is already
 *       earth-frame by definition ("Yaw in absolute frame relative to Earth's North, north is 0").
 *       The plain {@code yaw} field (documented "relative to vehicle") is <b>never</b> used for
 *       {@link Attitude#gimbalYawDegrees()} -- only {@code yaw_absolute}, or {@code null} if that is
 *       {@code NaN} (a sender old enough to never have implemented the extension).</li>
 * </ul>
 *
 * <p>Package-private mutable struct, not a record -- see {@link PositionAndPowerState}'s javadoc for
 * the same "one per decoder, no locking, no accessors" reasoning, which applies identically here.
 */
final class AttitudeState {

    private Double rollDegrees;
    private Double pitchDegrees;
    private Double yawDegrees;
    private Double gimbalRollDegrees;
    private Double gimbalPitchDegrees;
    private Double gimbalYawDegrees;
    private boolean preferredGimbalSourceSeen;

    void applyAttitude(io.dronefleet.mavlink.common.Attitude attitude) {
        rollDegrees = finiteOrNull(Math.toDegrees(attitude.roll()));
        pitchDegrees = finiteOrNull(Math.toDegrees(attitude.pitch()));
        yawDegrees = finiteOrNull(Math.toDegrees(attitude.yaw()));
    }

    void applyGimbalDeviceAttitudeStatus(GimbalDeviceAttitudeStatus status) {
        preferredGimbalSourceSeen = true;

        List<Float> q = status.q();
        QuaternionEuler.Euler euler = QuaternionEuler.fromQuaternion(q.get(0), q.get(1), q.get(2), q.get(3));
        gimbalRollDegrees = finiteOrNull(euler.rollDegrees());
        gimbalPitchDegrees = finiteOrNull(euler.pitchDegrees());
        gimbalYawDegrees = earthFrameYawDegrees(status, euler.yawDegrees());
    }

    void applyMountOrientation(MountOrientation orientation) {
        if (preferredGimbalSourceSeen) {
            return; // #285 has arrived at least once: never let the deprecated fallback overwrite it
        }
        gimbalRollDegrees = finiteOrNull(orientation.roll());
        gimbalPitchDegrees = finiteOrNull(orientation.pitch());
        // orientation.yaw() is vehicle-relative and deliberately never used here -- see class javadoc.
        gimbalYawDegrees = finiteOrNull(orientation.yawAbsolute());
    }

    /** {@code null} until at least one of the six components has actually become known. */
    Attitude toAttitudeOrNull() {
        if (rollDegrees == null && pitchDegrees == null && yawDegrees == null
                && gimbalRollDegrees == null && gimbalPitchDegrees == null && gimbalYawDegrees == null) {
            return null;
        }
        return new Attitude(rollDegrees, pitchDegrees, yawDegrees, gimbalRollDegrees, gimbalPitchDegrees,
                gimbalYawDegrees);
    }

    /** Resolves #285's yaw to earth-frame, or {@code null} if it cannot be -- see class javadoc. */
    private static Double earthFrameYawDegrees(GimbalDeviceAttitudeStatus status, double decodedYawDegrees) {
        EnumValue<GimbalDeviceFlags> flags = status.flags();
        if (flags.flagsEnabled(GimbalDeviceFlags.GIMBAL_DEVICE_FLAGS_YAW_IN_EARTH_FRAME)) {
            return finiteOrNull(decodedYawDegrees);
        }
        if (flags.flagsEnabled(GimbalDeviceFlags.GIMBAL_DEVICE_FLAGS_YAW_IN_VEHICLE_FRAME)) {
            return convertVehicleFrameYaw(status, decodedYawDegrees);
        }
        // Neither frame flag set: the spec's own "backwards compatibility" fallback. YAW_LOCK alone
        // decides the frame here, and delta_yaw must be ignored even if the sender populated it.
        if (flags.flagsEnabled(GimbalDeviceFlags.GIMBAL_DEVICE_FLAGS_YAW_LOCK)) {
            return finiteOrNull(decodedYawDegrees);
        }
        return null;
    }

    private static Double convertVehicleFrameYaw(GimbalDeviceAttitudeStatus status, double decodedYawDegrees) {
        float deltaYawRadians = status.deltaYaw();
        if (!Float.isFinite(deltaYawRadians)) {
            return null; // vehicle-frame yaw with no correction available: never report it as earth-frame
        }
        return finiteOrNull(decodedYawDegrees + Math.toDegrees(deltaYawRadians));
    }

    private static Double finiteOrNull(double value) {
        return Double.isFinite(value) ? value : null;
    }
}

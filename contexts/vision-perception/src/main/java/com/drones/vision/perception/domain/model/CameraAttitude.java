package com.drones.vision.perception.domain.model;

import com.drones.vision.kernel.Telemetry;

import java.time.Instant;
import java.util.Objects;

/**
 * Where the camera was pointing when a frame was captured — the input ego-motion compensation needs
 * (docs/conclusions/CV-RATE-BUDGET.md &sect;2, cv-service's {@code pose} compensator).
 *
 * <h2>Why this exists</h2>
 * On a drone the camera moves faster than the target. At 640&nbsp;px across a 60&deg; horizontal
 * field of view, a 30&deg;/s yaw displaces the whole scene by 32&nbsp;px between frames at 10&nbsp;fps,
 * while a 15&nbsp;m/s vehicle at 200&nbsp;m contributes about 4. An association gate that tolerates
 * ~13&nbsp;px for a 20&nbsp;px target therefore breaks on camera motion alone, with no occlusion and
 * no target motion at all. A compensator that is *told* the attitude delta can subtract it exactly,
 * instead of estimating it from pixels at greater cost and lower accuracy.
 *
 * <h2>{@code hfovDegrees} is what makes this usable</h2>
 * An attitude delta cannot become a pixel shift without a scale, so {@code 0} — the documented
 * "unknown" value — disables pose compensation for the frame rather than inventing one. That is the
 * normal state of a deployment that has not configured its camera's optics, and it is not an error:
 * the flow-based compensator still runs.
 *
 * <h2>What {@code yawDegrees} actually is today</h2>
 * It is fed from {@code Telemetry.headingDegrees}, which is the <b>airframe</b> heading, not the
 * camera boresight. For a fixed forward-facing camera those coincide, which is the case this is
 * built for. On a gimballed camera they do not, and this value will be wrong by the gimbal's pan
 * angle — populating it correctly needs gimbal feedback the system does not yet decode. Recorded
 * here rather than discovered later.
 *
 * @param yawDegrees   camera boresight heading in degrees; see the caveat above
 * @param pitchDegrees positive up. {@code 0} when unknown — a constant value simply contributes no
 *                     delta, so an unpopulated axis costs accuracy, never correctness
 * @param rollDegrees  positive clockwise; {@code 0} when unknown, same reasoning as pitch
 * @param hfovDegrees  horizontal field of view; {@code 0} = unknown, which disables pose compensation
 * @param vfovDegrees  vertical field of view; {@code 0} = derive from {@code hfovDegrees} and the
 *                     frame aspect ratio, which is what cv-service does
 * @param at           when this attitude was sampled; never {@code null}
 */
public record CameraAttitude(double yawDegrees, double pitchDegrees, double rollDegrees,
                              double hfovDegrees, double vfovDegrees, Instant at) {

    /** Field-of-view values are angles across a frame: at or above this they are nonsense, not optics. */
    private static final double MAX_FOV_DEGREES = 180.0;

    public CameraAttitude {
        if (at == null) {
            throw new IllegalArgumentException("CameraAttitude at must not be null");
        }
        if (!Double.isFinite(yawDegrees) || !Double.isFinite(pitchDegrees) || !Double.isFinite(rollDegrees)) {
            throw new IllegalArgumentException("CameraAttitude angles must be finite, was yaw=" + yawDegrees
                    + " pitch=" + pitchDegrees + " roll=" + rollDegrees);
        }
        if (!Double.isFinite(hfovDegrees) || hfovDegrees < 0.0 || hfovDegrees >= MAX_FOV_DEGREES) {
            throw new IllegalArgumentException(
                    "CameraAttitude hfovDegrees must be in [0, 180), was " + hfovDegrees);
        }
        if (!Double.isFinite(vfovDegrees) || vfovDegrees < 0.0 || vfovDegrees >= MAX_FOV_DEGREES) {
            throw new IllegalArgumentException(
                    "CameraAttitude vfovDegrees must be in [0, 180), was " + vfovDegrees);
        }
    }

    /**
     * The common case: a heading and a configured horizontal FOV, with pitch/roll unknown and the
     * vertical FOV left for cv-service to derive from the frame's aspect ratio.
     *
     * @param yawDegrees  camera heading
     * @param hfovDegrees horizontal field of view; {@code 0} = unknown
     * @param at          when this attitude was sampled
     * @return an attitude carrying yaw and scale only
     */
    public static CameraAttitude ofYaw(double yawDegrees, double hfovDegrees, Instant at) {
        return new CameraAttitude(yawDegrees, 0.0, 0.0, hfovDegrees, 0.0, at);
    }

    /**
     * @return whether this attitude can actually drive pose compensation. Mirrors cv-service's own
     *         {@code CameraPose.known} exactly — one definition of "usable", asserted on both sides
     *         of the wire rather than assumed to agree
     */
    public boolean known() {
        return hfovDegrees > 0.0;
    }

    /**
     * Builds an attitude from a telemetry sample, or {@code null} when the sample cannot supply one
     * — no telemetry at all, or no heading in it. Returning {@code null} rather than a zeroed
     * attitude is deliberate: a yaw of {@code 0.0} is a real bearing (due north), so a placeholder
     * would be indistinguishable from a genuine reading and would feed the compensator a fabricated
     * delta every time telemetry dropped out.
     *
     * @param telemetry   the sample to read, may be {@code null}
     * @param hfovDegrees the configured horizontal field of view; {@code 0} = unknown
     * @return the attitude, or {@code null} when none can be built
     */
    public static CameraAttitude from(Telemetry telemetry, double hfovDegrees) {
        if (telemetry == null || telemetry.headingDegrees() == null) {
            return null;
        }
        return ofYaw(telemetry.headingDegrees(), hfovDegrees, Objects.requireNonNull(telemetry.at()));
    }
}

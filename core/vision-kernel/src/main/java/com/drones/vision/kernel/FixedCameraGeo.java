package com.drones.vision.kernel;

import java.util.Optional;

/**
 * Pixel → ray → ground for a stationary camera (docs/plans/active/FIXED-CAMERA-GEO-PLAN.md §3,
 * decision D1) — the half of the projection {@link GeoProjection}'s own javadoc says does not
 * exist anywhere in the platform: "no camera-intrinsics data exists anywhere in the platform, so a
 * detection's position within the frame never moves the projected point." This class is that
 * missing half. It turns one pixel of a fixed camera's frame into an azimuth/depression ray from
 * the camera's pose, then hands that ray to the untouched, already-shipped
 * {@link GeoProjection#project} for the ray→ground spherical math — one source of truth for that
 * half, zero risk to the drone path.
 *
 * <p>Pixel model (small-rotation, roll = 0 assumed): a pixel's horizontal offset from center maps
 * to an azimuth offset via {@code atan((2u-1)*tan(hfov/2))}, and its vertical offset maps to a
 * depression offset the same way, scaled by the frame's aspect ratio (square pixels assumed:
 * {@code tan(vfov/2) = tan(hfov/2)*height/width}). This ignores the small cross-axis coupling a
 * true pinhole model has between azimuth and depression away from boresight — under ~2 degrees for
 * pitch &lt;= 30 degrees — and a fixed camera's calibration absorbs the residual bias. Stated here,
 * not hidden.
 *
 * <p>{@code v} is measured from the top of the frame, so a tracked object's bounding-box
 * bottom-centre — its ground-contact point — is {@code (x + width/2, y + height)} on
 * {@link BoundingBox}, exactly the point {@link #project} projects.
 *
 * <p><b>Honesty over completeness (decision D6):</b> a ray that never meets the ground under the
 * flat-ground assumption near the horizon, a fix beyond the configured max range, or a fix whose
 * error radius exceeds the configured ceiling never becomes a coordinate — {@link #project}
 * returns {@link Optional#empty()} rather than a confident-looking point it cannot stand behind.
 * The same refusal also covers the (physically nonsensical, but mathematically reachable for
 * extreme configured pitch/hfov/pixel combinations) case where the combined depression exceeds 90
 * degrees — past straight down there is no forward ground intersection either, so it is folded
 * into the same "no ground intersection" refusal rather than left to throw out of
 * {@link GeoProjection#project}'s own {@code (0,90]} contract.
 *
 * <p>Not instantiable: a stateless collection of static functions, mirroring {@link GeoProjection}
 * (java-clean-code SKILL.md §1 — one implementation, no substitution point, no interface earned).
 */
public final class FixedCameraGeo {

    private FixedCameraGeo() {
    }

    /**
     * Projects one tracked object's bounding box, seen by a fixed camera, onto the ground.
     *
     * @param pose               the camera's fixed position, aim and field of view
     * @param imageWidthPixels   the frame's width in pixels; must be positive
     * @param imageHeightPixels  the frame's height in pixels; must be positive
     * @param boundingBox        the tracked object's box in the frame, normalized [0,1]; this
     *                           method projects the box's bottom-centre — its ground-contact
     *                           point, not its geometric center
     * @param settings           the configured horizon guard, range ceiling, angular error and
     *                           error ceiling (D6) that decide whether the fix is honest enough to
     *                           publish
     * @return the ground fix, or {@link Optional#empty()} if the ray does not meet the flat-ground
     *         assumption near or beyond the horizon, its range exceeds {@code settings}' ceiling,
     *         or its error radius exceeds {@code settings}' ceiling
     * @throws IllegalArgumentException if {@code pose}, {@code boundingBox} or {@code settings} is
     *                                   {@code null}, or either image dimension is not positive
     */
    public static Optional<GroundFix> project(FixedCameraPose pose, int imageWidthPixels, int imageHeightPixels,
                                                BoundingBox boundingBox, FixedCameraGeoSettings settings) {
        if (pose == null) {
            throw new IllegalArgumentException("FixedCameraGeo.project pose must not be null");
        }
        if (boundingBox == null) {
            throw new IllegalArgumentException("FixedCameraGeo.project boundingBox must not be null");
        }
        if (settings == null) {
            throw new IllegalArgumentException("FixedCameraGeo.project settings must not be null");
        }
        if (imageWidthPixels <= 0) {
            throw new IllegalArgumentException(
                    "FixedCameraGeo.project imageWidthPixels must be positive: " + imageWidthPixels);
        }
        if (imageHeightPixels <= 0) {
            throw new IllegalArgumentException(
                    "FixedCameraGeo.project imageHeightPixels must be positive: " + imageHeightPixels);
        }

        double groundContactU = boundingBox.x() + boundingBox.width() / 2.0;
        double groundContactV = boundingBox.y() + boundingBox.height();

        double halfHfovTangent = Math.tan(Math.toRadians(pose.hfovDegrees()) / 2.0);
        double aspectRatio = (double) imageHeightPixels / (double) imageWidthPixels;

        double azimuthOffsetDegrees = Math.toDegrees(Math.atan((2.0 * groundContactU - 1.0) * halfHfovTangent));
        double depressionOffsetDegrees =
                Math.toDegrees(Math.atan((2.0 * groundContactV - 1.0) * halfHfovTangent * aspectRatio));

        double azimuthDegrees = pose.yawDegrees() + azimuthOffsetDegrees;
        double depressionDegrees = pose.pitchDegrees() + depressionOffsetDegrees;

        // Refusal 1 (D6): at or above the horizon, or past the (0,90] range GeoProjection.project
        // itself accepts — either way, no ground intersection under flat-ground.
        if (depressionDegrees < settings.minRayDepressionDegrees() || depressionDegrees > 90.0) {
            return Optional.empty();
        }

        double rangeMeters = pose.aglMeters() / Math.tan(Math.toRadians(depressionDegrees));
        // Refusal 2 (D6): beyond the configured range ceiling, the flat-ground fiction stops being
        // useful.
        if (rangeMeters > settings.maxRangeMeters()) {
            return Optional.empty();
        }

        double errorRadiusMeters = errorRadiusMeters(pose.aglMeters(), rangeMeters, depressionDegrees,
                settings.angularErrorDegrees());
        // Refusal 3 (D6): a fix less certain than the configured ceiling is dropped, never
        // published.
        if (errorRadiusMeters > settings.maxErrorRadiusMeters()) {
            return Optional.empty();
        }

        GeoPosition ground =
                GeoProjection.project(pose.position(), azimuthDegrees, pose.aglMeters(), depressionDegrees);
        return Optional.of(new GroundFix(ground, rangeMeters, errorRadiusMeters));
    }

    /**
     * The one-sigma ground-plane error radius for a ray at {@code depressionDegrees} from height
     * {@code aglMeters}, given assumed angular error {@code angularErrorDegrees} (D6): downrange
     * error grows as {@code 1/sin^2(depression)} — a fixed angular uncertainty sweeps a larger
     * ground distance the shallower the ray gets, which is also why refusal 1 exists at all —
     * crossrange error grows linearly with range. The larger of the two is reported, since it is
     * the axis the true position is least likely to beat.
     */
    private static double errorRadiusMeters(double aglMeters, double rangeMeters, double depressionDegrees,
                                             double angularErrorDegrees) {
        double angularErrorRadians = Math.toRadians(angularErrorDegrees);
        double sinDepression = Math.sin(Math.toRadians(depressionDegrees));
        double downrangeMeters = aglMeters * angularErrorRadians / (sinDepression * sinDepression);
        double crossrangeMeters = rangeMeters * angularErrorRadians;
        return Math.max(downrangeMeters, crossrangeMeters);
    }
}

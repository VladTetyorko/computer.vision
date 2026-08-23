package com.drones.vision.kernel;

/**
 * The operator-tunable limits {@link FixedCameraGeo} uses to decide whether a projected ground fix
 * is honest enough to publish (docs/plans/done/FIXED-CAMERA-GEO-PLAN.md decision D6). CLAUDE.md
 * rule 1 forbids these as code constants — every caller supplies them, sourced from
 * {@code application.yaml}'s {@code vision.geo.fixed-camera.*} in the running app (§6); the kernel
 * itself stores no default.
 *
 * @param minRayDepressionDegrees below this depression the ray is at or above the effective
 *                                horizon and does not meet flat ground; must be within {@code
 *                                (0,90]}
 * @param maxRangeMeters          beyond this ground range the flat-ground assumption stops being
 *                                useful; must be positive
 * @param angularErrorDegrees     the assumed one-sigma angular error (pixel quantization + pose
 *                                error) used to size {@link GroundFix#errorRadiusMeters()}; must be
 *                                positive
 * @param maxErrorRadiusMeters    a fix less certain than this is refused rather than published;
 *                                must be positive
 */
public record FixedCameraGeoSettings(double minRayDepressionDegrees, double maxRangeMeters,
                                      double angularErrorDegrees, double maxErrorRadiusMeters) {

    public FixedCameraGeoSettings {
        if (Double.isNaN(minRayDepressionDegrees) || minRayDepressionDegrees <= 0.0
                || minRayDepressionDegrees > 90.0) {
            throw new IllegalArgumentException(
                    "FixedCameraGeoSettings minRayDepressionDegrees must be within (0,90]: "
                            + minRayDepressionDegrees);
        }
        if (Double.isNaN(maxRangeMeters) || Double.isInfinite(maxRangeMeters) || maxRangeMeters <= 0.0) {
            throw new IllegalArgumentException(
                    "FixedCameraGeoSettings maxRangeMeters must be positive: " + maxRangeMeters);
        }
        if (Double.isNaN(angularErrorDegrees) || Double.isInfinite(angularErrorDegrees) || angularErrorDegrees <= 0.0) {
            throw new IllegalArgumentException(
                    "FixedCameraGeoSettings angularErrorDegrees must be positive: " + angularErrorDegrees);
        }
        if (Double.isNaN(maxErrorRadiusMeters) || Double.isInfinite(maxErrorRadiusMeters) || maxErrorRadiusMeters <= 0.0) {
            throw new IllegalArgumentException(
                    "FixedCameraGeoSettings maxErrorRadiusMeters must be positive: " + maxErrorRadiusMeters);
        }
    }
}

package com.drones.vision.api.support;

/**
 * Framework-free tunables for the fixed-camera geolocation feature's {@code vision-api} edge
 * (docs/plans/done/FIXED-CAMERA-GEO-PLAN.md §5/§6/D8), the same "plain settings record, {@code
 * vision-app} binds its Spring-{@code @ConfigurationProperties} mirror onto an instance of this"
 * bridge {@link VisionApiProperties}'s own javadoc documents in full and {@link
 * OnboardingProperties} already follows — {@code vision-api} may not depend on {@code vision-app},
 * so its own {@code @ConfigurationProperties}-annotated {@code VisionGeoProperties} cannot be
 * referenced here directly.
 *
 * @param enabled                     {@code vision.geo.fixed-camera.enabled} (D8, default {@code
 *                                     false}) — {@code CameraPoseController}/{@code
 *                                     MapTracksController} throw {@link IllegalStateException} on
 *                                     every method when this is {@code false}, mapped to the frozen
 *                                     §5 {@code 409}
 * @param calibrationMaxRmsErrorPixels the residual ceiling {@code CameraCalibrationSolver#solve}'s
 *                                     second argument needs (D5) — {@code
 *                                     vision.geo.fixed-camera.calibration.max-rms-error-pixels}
 */
public record FixedCameraGeoProperties(boolean enabled, double calibrationMaxRmsErrorPixels) {

    /** The frozen §5 flag-off refusal text, verbatim on the wire via {@link IllegalStateException}. */
    public static final String DISABLED_MESSAGE =
            "fixed-camera geolocation is disabled (vision.geo.fixed-camera.enabled)";

    public FixedCameraGeoProperties {
        if (calibrationMaxRmsErrorPixels <= 0.0) {
            throw new IllegalArgumentException(
                    "calibrationMaxRmsErrorPixels must be positive: " + calibrationMaxRmsErrorPixels);
        }
    }

    /**
     * Throws the frozen §5 {@code 409} unless the feature is on.
     *
     * @throws IllegalStateException if {@link #enabled()} is {@code false} — {@code
     *                                ApiExceptionHandler} maps this to {@code 409} with {@link
     *                                #DISABLED_MESSAGE} verbatim
     */
    public void requireEnabled() {
        if (!enabled) {
            throw new IllegalStateException(DISABLED_MESSAGE);
        }
    }
}

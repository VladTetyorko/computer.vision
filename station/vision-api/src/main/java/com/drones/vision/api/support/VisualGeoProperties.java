package com.drones.vision.api.support;

/**
 * Framework-free tunables for visual geolocation's {@code vision-api} edge
 * (docs/plans/active/VISUAL-GEO-V2-PLAN.md §3.3/D9), the same "plain settings record, {@code
 * vision-app} binds its Spring-{@code @ConfigurationProperties} mirror onto an instance of this"
 * bridge {@link FixedCameraGeoProperties}'s own javadoc documents in full — {@code vision-api} may
 * not depend on {@code vision-app}, so its own {@code @ConfigurationProperties}-annotated {@code
 * VisionGeoVisualProperties} cannot be referenced here directly.
 *
 * @param enabled {@code vision.geo.visual.enabled} (D9, default {@code false}) — {@code
 *                GeoRegionController}/{@code GeoCorrectionController} throw {@link
 *                IllegalStateException} on every method when this is {@code false}, mapped to the
 *                frozen §3.3/D9 {@code 409}
 */
public record VisualGeoProperties(boolean enabled) {

    /** The frozen D9 flag-off refusal text, verbatim on the wire via {@link IllegalStateException}. */
    public static final String DISABLED_MESSAGE = "visual geolocation is disabled (vision.geo.visual.enabled)";

    /**
     * Throws the frozen D9 {@code 409} unless the feature is on.
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

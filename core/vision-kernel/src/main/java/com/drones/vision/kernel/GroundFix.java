package com.drones.vision.kernel;

/**
 * A ground point {@link FixedCameraGeo} has projected a tracked object onto, carried alongside the
 * range and uncertainty that produced it (docs/plans/active/FIXED-CAMERA-GEO-PLAN.md §3) — so a
 * consumer can draw the honest error circle decision D6 requires, never a confident-looking dot
 * for a fix the math cannot stand behind.
 *
 * @param position          the estimated ground point; {@code altitudeMeters} is always
 *                          {@code null} (no terrain model — the same convention
 *                          {@link GeoProjection#project} uses)
 * @param rangeMeters       ground range from the camera to {@code position}, meters; never
 *                          negative
 * @param errorRadiusMeters the one-sigma-derived radius of uncertainty around {@code position},
 *                          meters; never negative
 */
public record GroundFix(GeoPosition position, double rangeMeters, double errorRadiusMeters) {

    public GroundFix {
        if (position == null) {
            throw new IllegalArgumentException("GroundFix position must not be null");
        }
        if (Double.isNaN(rangeMeters) || Double.isInfinite(rangeMeters) || rangeMeters < 0.0) {
            throw new IllegalArgumentException("GroundFix rangeMeters must not be negative: " + rangeMeters);
        }
        if (Double.isNaN(errorRadiusMeters) || Double.isInfinite(errorRadiusMeters) || errorRadiusMeters < 0.0) {
            throw new IllegalArgumentException(
                    "GroundFix errorRadiusMeters must not be negative: " + errorRadiusMeters);
        }
    }
}

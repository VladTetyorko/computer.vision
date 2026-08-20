package com.drones.vision.kernel;

import java.time.Instant;

/**
 * The measured result of one HEAVY-A visual-geolocation attempt against a single keyframe —
 * cv-service's {@code GeoFix} (docs/plans/active/VISUAL-GEO-V2-PLAN.md §3.1), carried into Java
 * unchanged. A pure value with two context consumers — {@code vision-perception} produces it (the
 * driving side of the localization session, H2b) and {@code vision-flight} consumes it (building the
 * aircraft's own corrected track, H2a §3.5) — the same two-consumer justification {@link Telemetry}
 * itself passes for living in the kernel rather than either context (VISUAL-GEO-V2-PLAN.md D7).
 *
 * <p><b>A refusal, not an error</b>: {@code position == null} means the pipeline searched and found
 * nothing it believed (a gate refused it, or no region/index was available) — {@link #refusal()}
 * names which. This is never a thrown exception; a refused fix is exactly as valid a measurement as
 * an accepted one, and both are persisted the same way one layer up (VISUAL-GEO-V2-PLAN.md §4.5's
 * "{@code NO_FIX} persists with a non-empty refusal and a null position").
 *
 * <p>{@link #position()}'s own {@code altitudeMeters} is always {@code null} when present — this is a
 * 2-D horizontal fix from a homography, not an altitude measurement (the same convention {@code
 * GeoProjection.project} and {@code FixedCameraGeo.project} already use for their own outputs).
 *
 * @param frameAt            {@code capturedAt} of the frame this fix came from; never {@code null}
 * @param position           the fixed ground position; {@code null} exactly when {@link #refusal()}
 *                            is non-empty; {@code altitudeMeters} always {@code null} when present
 * @param yawDegrees          camera image-up bearing, nullable — present only when the homography
 *                            passed sanity
 * @param radiusMeters        1-sigma horizontal uncertainty, meters; nullable; non-negative when
 *                            present
 * @param impliedAglMeters   AGL implied by the homography scale, meters; nullable
 * @param regionId            the region actually searched; {@code ""} when none was
 * @param tileId              {@code "<z>/<x>/<y>"} of the winning cell, for diagnostics; {@code ""}
 *                            when none
 * @param refusal             names the first gate that refused this frame; {@code ""} iff {@link
 *                            #position()} is non-null
 * @param evidence            why the pipeline believes (or refused) this fix; never {@code null}
 * @param telemetryAgeMillis  {@code frameAt} minus the telemetry sample's own timestamp, signed and
 *                            honest (a negative value is a clock skew worth seeing, not clamping away)
 * @param latencyMillis       worker-side compute cost for this frame, milliseconds
 */
public record VisualFix(
        Instant frameAt,
        GeoPosition position,
        Double yawDegrees,
        Double radiusMeters,
        Double impliedAglMeters,
        String regionId,
        String tileId,
        String refusal,
        VisualFixEvidence evidence,
        long telemetryAgeMillis,
        long latencyMillis) {

    public VisualFix {
        if (frameAt == null) {
            throw new IllegalArgumentException("VisualFix frameAt must not be null");
        }
        if (yawDegrees != null && (Double.isNaN(yawDegrees) || Double.isInfinite(yawDegrees))) {
            throw new IllegalArgumentException("VisualFix yawDegrees must be finite: " + yawDegrees);
        }
        if (radiusMeters != null
                && (Double.isNaN(radiusMeters) || Double.isInfinite(radiusMeters) || radiusMeters < 0)) {
            throw new IllegalArgumentException("VisualFix radiusMeters must be finite and non-negative: "
                    + radiusMeters);
        }
        if (impliedAglMeters != null && (Double.isNaN(impliedAglMeters) || Double.isInfinite(impliedAglMeters))) {
            throw new IllegalArgumentException("VisualFix impliedAglMeters must be finite: " + impliedAglMeters);
        }
        if (regionId == null) {
            throw new IllegalArgumentException("VisualFix regionId must not be null (use \"\")");
        }
        if (tileId == null) {
            throw new IllegalArgumentException("VisualFix tileId must not be null (use \"\")");
        }
        if (refusal == null) {
            throw new IllegalArgumentException("VisualFix refusal must not be null (use \"\")");
        }
        if ((position != null) != refusal.isEmpty()) {
            throw new IllegalArgumentException(
                    "VisualFix position must be non-null iff refusal is empty: position=" + position
                            + ", refusal='" + refusal + "'");
        }
        if (position != null && position.altitudeMeters() != null) {
            throw new IllegalArgumentException(
                    "VisualFix position.altitudeMeters must be null (a 2-D homography fix): " + position);
        }
        if (evidence == null) {
            throw new IllegalArgumentException("VisualFix evidence must not be null");
        }
    }
}

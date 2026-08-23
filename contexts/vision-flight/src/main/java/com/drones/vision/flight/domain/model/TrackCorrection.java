package com.drones.vision.flight.domain.model;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.kernel.VisualFixEvidence;

import java.time.Instant;

/**
 * A second, parallel opinion about where the aircraft is — the aircraft's <b>own</b> position, not a
 * ground object's (docs/plans/done/VISUAL-GEO-V2-PLAN.md D7, §3.5), built from one {@code
 * VisualFix} plus the aircraft's own reported telemetry at the same frame time. Persisted and
 * published unconditionally, {@link CorrectionStatus#NO_FIX} included — a refusal is a measurement,
 * not an error (the same posture {@link com.drones.vision.kernel.VisualFix} itself takes).
 *
 * <h2>Invariants (compact constructor)</h2>
 * <ul>
 *   <li>{@link #position()} is {@code null} exactly when {@link #status()} is {@link
 *       CorrectionStatus#NO_FIX}, and exactly when {@link #refusal()} is non-empty — the same
 *       three-way agreement {@link com.drones.vision.kernel.VisualFix} enforces for its own {@code
 *       position}/{@code refusal} pair.</li>
 *   <li>{@link #divergent()} is {@code true} exactly when {@link #divergentSince()} is non-null — a
 *       design choice this type makes that §4.5 does not spell out explicitly (see this class's own
 *       Gotcha note in {@code contexts/vision-flight/MODULE.md}): once the alarm clears, a later
 *       correction reports {@code divergent=false, divergentSince=null} rather than retaining the
 *       cleared alarm's start time.</li>
 * </ul>
 *
 * @param assetId            the aircraft this correction is about
 * @param usageId            the flight session this correction belongs to
 * @param frameAt            {@code capturedAt} of the keyframe the underlying {@code VisualFix} came
 *                           from
 * @param computedAt         when this context finished gating and produced this correction
 * @param status             how much this correction is believed (§4.3's Java-side gates)
 * @param source             which correction technique produced this — one value today
 * @param position           the corrected ground position; {@code null} iff {@code status ==
 *                           NO_FIX}; {@code altitudeMeters} always {@code null} when present (a 2-D
 *                           homography fix)
 * @param yawDegrees          camera image-up bearing, nullable
 * @param radiusMeters        1-sigma horizontal uncertainty of {@link #position()}, meters; nullable;
 *                            non-negative when present
 * @param impliedAglMeters   AGL implied by the homography scale, meters; nullable
 * @param rawPosition         the aircraft's own reported fix at {@link #frameAt()}; nullable (a
 *                            telemetry gap) — never modified, never overwritten (CLAUDE.md rule 9)
 * @param separationMeters    great-circle distance from {@link #rawPosition()} to {@link
 *                            #position()} ({@code GeoProjection.bearingDistance}); {@code null}
 *                            whenever either position is absent; non-negative when present
 * @param sigmaMeters          {@code sqrt(rawRadius^2 + radiusMeters^2)}, the combined 1-sigma the
 *                            divergence gate compares {@link #separationMeters()} against; {@code
 *                            null} whenever {@link #separationMeters()} is; non-negative when present
 * @param divergent            whether the divergence alarm is currently latched for this asset
 *                            (§4.5); reported on every correction, not only the ones that changed it
 * @param divergentSince      when the currently-latched alarm first rose; {@code null} iff {@code
 *                            !divergent}
 * @param regionId             the region actually searched; {@code ""} when none was
 * @param tileId               {@code "<z>/<x>/<y>"} of the winning cell; {@code ""} when none
 * @param refusal              names the first gate that refused this correction (Python- or
 *                            Java-side); {@code ""} iff {@link #position()} is non-null
 * @param evidence             the underlying {@code VisualFix}'s evidence, carried unmodified
 */
public record TrackCorrection(
        AssetId assetId, UsageId usageId,
        Instant frameAt, Instant computedAt,
        CorrectionStatus status, CorrectionSource source,
        GeoPosition position,
        Double yawDegrees, Double radiusMeters, Double impliedAglMeters,
        GeoPosition rawPosition,
        Double separationMeters, Double sigmaMeters,
        boolean divergent, Instant divergentSince,
        String regionId, String tileId, String refusal,
        VisualFixEvidence evidence) {

    public TrackCorrection {
        if (assetId == null) {
            throw new IllegalArgumentException("TrackCorrection assetId must not be null");
        }
        if (usageId == null) {
            throw new IllegalArgumentException("TrackCorrection usageId must not be null");
        }
        if (frameAt == null) {
            throw new IllegalArgumentException("TrackCorrection frameAt must not be null");
        }
        if (computedAt == null) {
            throw new IllegalArgumentException("TrackCorrection computedAt must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("TrackCorrection status must not be null");
        }
        if (source == null) {
            throw new IllegalArgumentException("TrackCorrection source must not be null");
        }
        if (regionId == null) {
            throw new IllegalArgumentException("TrackCorrection regionId must not be null (use \"\")");
        }
        if (tileId == null) {
            throw new IllegalArgumentException("TrackCorrection tileId must not be null (use \"\")");
        }
        if (refusal == null) {
            throw new IllegalArgumentException("TrackCorrection refusal must not be null (use \"\")");
        }
        if (evidence == null) {
            throw new IllegalArgumentException("TrackCorrection evidence must not be null");
        }
        if ((position == null) != (status == CorrectionStatus.NO_FIX)) {
            throw new IllegalArgumentException(
                    "TrackCorrection position must be null iff status == NO_FIX: position=" + position
                            + ", status=" + status);
        }
        if ((position != null) != refusal.isEmpty()) {
            throw new IllegalArgumentException(
                    "TrackCorrection position must be non-null iff refusal is empty: position=" + position
                            + ", refusal='" + refusal + "'");
        }
        if (position != null && position.altitudeMeters() != null) {
            throw new IllegalArgumentException(
                    "TrackCorrection position.altitudeMeters must be null (a 2-D homography fix): " + position);
        }
        if (yawDegrees != null && (Double.isNaN(yawDegrees) || Double.isInfinite(yawDegrees))) {
            throw new IllegalArgumentException("TrackCorrection yawDegrees must be finite: " + yawDegrees);
        }
        if (radiusMeters != null
                && (Double.isNaN(radiusMeters) || Double.isInfinite(radiusMeters) || radiusMeters < 0)) {
            throw new IllegalArgumentException("TrackCorrection radiusMeters must be finite and non-negative: "
                    + radiusMeters);
        }
        if (impliedAglMeters != null && (Double.isNaN(impliedAglMeters) || Double.isInfinite(impliedAglMeters))) {
            throw new IllegalArgumentException("TrackCorrection impliedAglMeters must be finite: "
                    + impliedAglMeters);
        }
        if (separationMeters != null
                && (Double.isNaN(separationMeters) || Double.isInfinite(separationMeters) || separationMeters < 0)) {
            throw new IllegalArgumentException("TrackCorrection separationMeters must be finite and non-negative: "
                    + separationMeters);
        }
        if (sigmaMeters != null && (Double.isNaN(sigmaMeters) || Double.isInfinite(sigmaMeters) || sigmaMeters < 0)) {
            throw new IllegalArgumentException("TrackCorrection sigmaMeters must be finite and non-negative: "
                    + sigmaMeters);
        }
        if (divergent != (divergentSince != null)) {
            throw new IllegalArgumentException(
                    "TrackCorrection divergentSince must be non-null iff divergent: divergent=" + divergent
                            + ", divergentSince=" + divergentSince);
        }
    }
}

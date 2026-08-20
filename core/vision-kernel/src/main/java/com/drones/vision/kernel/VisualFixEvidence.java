package com.drones.vision.kernel;

/**
 * Why a {@link VisualFix} believes (or refuses) what it claims — the wire-verbatim evidence bundle
 * cv-service reports alongside every fix (docs/plans/active/VISUAL-GEO-V2-PLAN.md §3.1's {@code
 * GeoEvidence}, §3.2). An operator must always be able to see <em>why</em>, not just <em>what</em>
 * (VISUAL-GEO-V2-PLAN.md D5) — this record is that "why", carried unmodified from the Python re-rank
 * and sequence-filter stages through to the wire.
 *
 * @param candidateCount        top-k candidates actually re-ranked; never negative
 * @param matchCount             correspondences found on the winning candidate; never negative
 * @param inlierCount            MAGSAC inliers on the winning candidate — the promotion gate; never
 *                               negative
 * @param inlierRatio            {@code inlierCount / matchCount}; finite, never negative
 * @param rerankMargin           {@code (s1 - s2) / max(s1, 1)} over inlier scores, {@code 0} when
 *                               fewer than two candidates were re-ranked; finite
 * @param reprojectionRmsPixels  homography residual, in pixels — the ceiling gate applies at every
 *                               match count (§4.2 G-c); finite, never negative
 * @param rectified              {@code true} iff IPM rectification was actually applied ({@code
 *                               false} = degraded to the raw frame — no telemetry, or telemetry too
 *                               stale)
 * @param cellCalibrated         {@code true} iff the winning tile cell has a real, self-calibrated
 *                               accept threshold rather than the never-accept sentinel (§4.2 G-e)
 * @param supportingFrames       distinct frames whose own inliers cleared the single-frame floor,
 *                               feeding the sequence filter's convergence; never negative
 * @param baselineMeters         platform motion spanned by {@code supportingFrames}, from telemetry;
 *                               finite, never negative
 * @param sequenceConverged      {@code true} iff the particle filter reports {@code CONVERGED} under
 *                               the full §4.4 false-convergence gate (calibration + diversity/baseline
 *                               + spread ceiling), not spread alone
 * @param sequenceSpreadMeters   the filter's posterior 1-sigma spread, meters; finite, never negative
 * @param sequenceUpdates        how many filter updates contributed; never negative
 * @param osmPrior               the D8 OSM tie-breaker's multiplier; {@code 1.0} is inert (the
 *                               shipped default, {@code CV_GEO_OSM_WEIGHT=0}); finite, never negative
 */
public record VisualFixEvidence(
        int candidateCount, int matchCount, int inlierCount,
        double inlierRatio, double rerankMargin, double reprojectionRmsPixels,
        boolean rectified, boolean cellCalibrated,
        int supportingFrames, double baselineMeters,
        boolean sequenceConverged, double sequenceSpreadMeters, int sequenceUpdates,
        double osmPrior) {

    public VisualFixEvidence {
        if (candidateCount < 0) {
            throw new IllegalArgumentException("VisualFixEvidence candidateCount must not be negative: "
                    + candidateCount);
        }
        if (matchCount < 0) {
            throw new IllegalArgumentException("VisualFixEvidence matchCount must not be negative: " + matchCount);
        }
        if (inlierCount < 0) {
            throw new IllegalArgumentException("VisualFixEvidence inlierCount must not be negative: "
                    + inlierCount);
        }
        if (Double.isNaN(inlierRatio) || Double.isInfinite(inlierRatio) || inlierRatio < 0) {
            throw new IllegalArgumentException("VisualFixEvidence inlierRatio must be finite and non-negative: "
                    + inlierRatio);
        }
        if (Double.isNaN(rerankMargin) || Double.isInfinite(rerankMargin)) {
            throw new IllegalArgumentException("VisualFixEvidence rerankMargin must be finite: " + rerankMargin);
        }
        if (Double.isNaN(reprojectionRmsPixels) || Double.isInfinite(reprojectionRmsPixels)
                || reprojectionRmsPixels < 0) {
            throw new IllegalArgumentException(
                    "VisualFixEvidence reprojectionRmsPixels must be finite and non-negative: "
                            + reprojectionRmsPixels);
        }
        if (supportingFrames < 0) {
            throw new IllegalArgumentException("VisualFixEvidence supportingFrames must not be negative: "
                    + supportingFrames);
        }
        if (Double.isNaN(baselineMeters) || Double.isInfinite(baselineMeters) || baselineMeters < 0) {
            throw new IllegalArgumentException("VisualFixEvidence baselineMeters must be finite and non-negative: "
                    + baselineMeters);
        }
        if (Double.isNaN(sequenceSpreadMeters) || Double.isInfinite(sequenceSpreadMeters)
                || sequenceSpreadMeters < 0) {
            throw new IllegalArgumentException(
                    "VisualFixEvidence sequenceSpreadMeters must be finite and non-negative: "
                            + sequenceSpreadMeters);
        }
        if (sequenceUpdates < 0) {
            throw new IllegalArgumentException("VisualFixEvidence sequenceUpdates must not be negative: "
                    + sequenceUpdates);
        }
        if (Double.isNaN(osmPrior) || Double.isInfinite(osmPrior) || osmPrior < 0) {
            throw new IllegalArgumentException("VisualFixEvidence osmPrior must be finite and non-negative: "
                    + osmPrior);
        }
    }
}

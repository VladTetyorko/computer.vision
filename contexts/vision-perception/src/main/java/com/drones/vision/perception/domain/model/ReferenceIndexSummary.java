package com.drones.vision.perception.domain.model;

import java.time.Instant;

/**
 * A built reference region's identity and self-calibrated stats — the Java-side merge of proto's
 * {@code RegionInfo} and its nested {@code ReferenceIndexStats} (docs/plans/active/
 * VISUAL-GEO-V2-PLAN.md §3.1), exactly what {@link
 * com.drones.vision.perception.domain.port.ReferenceIndexPort#list} returns per region: cv-service
 * is the source of truth for both identity and stats (D10), so there is nothing here Java itself
 * computed.
 *
 * @param regionId                 the region's identifier
 * @param name                     operator-facing display name
 * @param bounds                   the region's bounding box
 * @param zoom                     the zoom level it was built at
 * @param builtAt                  when the build completed
 * @param tileCount                tiles actually indexed
 * @param descriptorCount          embeddings in the index
 * @param descriptorDim            embedding dimensionality
 * @param encoderId                which encoder produced the embeddings
 * @param acceptSimilarity         self-calibrated accept threshold on held-out tiles
 * @param acceptMargin             self-calibrated accept margin
 * @param holdoutRecallAt1         held-out recall@1
 * @param holdoutMedianErrorMeters held-out median localization error, meters
 * @param indexBytes               on-disk index size
 * @param neverAcceptCells         cells whose calibration refuses outright (§4.2 G-e) — a region
 *                                 where this equals {@code tileCount} surfaces as {@link
 *                                 RegionStatus#NEVER_ACCEPT}, never {@link RegionStatus#READY}
 */
public record ReferenceIndexSummary(
        String regionId, String name, RegionBounds bounds, int zoom, Instant builtAt,
        int tileCount, int descriptorCount, int descriptorDim, String encoderId,
        double acceptSimilarity, double acceptMargin,
        double holdoutRecallAt1, double holdoutMedianErrorMeters,
        long indexBytes, int neverAcceptCells) {

    public ReferenceIndexSummary {
        if (regionId == null || regionId.isBlank()) {
            throw new IllegalArgumentException("ReferenceIndexSummary regionId must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ReferenceIndexSummary name must not be blank");
        }
        if (bounds == null) {
            throw new IllegalArgumentException("ReferenceIndexSummary bounds must not be null");
        }
        if (zoom < 0) {
            throw new IllegalArgumentException("ReferenceIndexSummary zoom must not be negative: " + zoom);
        }
        if (builtAt == null) {
            throw new IllegalArgumentException("ReferenceIndexSummary builtAt must not be null");
        }
        if (tileCount < 0) {
            throw new IllegalArgumentException("ReferenceIndexSummary tileCount must not be negative: " + tileCount);
        }
        if (descriptorCount < 0) {
            throw new IllegalArgumentException(
                    "ReferenceIndexSummary descriptorCount must not be negative: " + descriptorCount);
        }
        if (descriptorDim < 0) {
            throw new IllegalArgumentException(
                    "ReferenceIndexSummary descriptorDim must not be negative: " + descriptorDim);
        }
        if (encoderId == null || encoderId.isBlank()) {
            throw new IllegalArgumentException("ReferenceIndexSummary encoderId must not be blank");
        }
        if (Double.isNaN(acceptSimilarity) || Double.isInfinite(acceptSimilarity)) {
            throw new IllegalArgumentException(
                    "ReferenceIndexSummary acceptSimilarity must be finite: " + acceptSimilarity);
        }
        if (Double.isNaN(acceptMargin) || Double.isInfinite(acceptMargin)) {
            throw new IllegalArgumentException("ReferenceIndexSummary acceptMargin must be finite: " + acceptMargin);
        }
        if (Double.isNaN(holdoutRecallAt1) || Double.isInfinite(holdoutRecallAt1) || holdoutRecallAt1 < 0) {
            throw new IllegalArgumentException(
                    "ReferenceIndexSummary holdoutRecallAt1 must be finite and non-negative: " + holdoutRecallAt1);
        }
        if (Double.isNaN(holdoutMedianErrorMeters) || Double.isInfinite(holdoutMedianErrorMeters)
                || holdoutMedianErrorMeters < 0) {
            throw new IllegalArgumentException(
                    "ReferenceIndexSummary holdoutMedianErrorMeters must be finite and non-negative: "
                            + holdoutMedianErrorMeters);
        }
        if (indexBytes < 0) {
            throw new IllegalArgumentException("ReferenceIndexSummary indexBytes must not be negative: "
                    + indexBytes);
        }
        if (neverAcceptCells < 0) {
            throw new IllegalArgumentException(
                    "ReferenceIndexSummary neverAcceptCells must not be negative: " + neverAcceptCells);
        }
    }

    /**
     * §4.2 G-e: a region every one of whose tiles is never-accept-calibrated is honestly
     * unusable — {@link RegionStatus#NEVER_ACCEPT}, never {@link RegionStatus#READY}.
     */
    public RegionStatus impliedStatus() {
        return tileCount > 0 && neverAcceptCells >= tileCount ? RegionStatus.NEVER_ACCEPT : RegionStatus.READY;
    }
}

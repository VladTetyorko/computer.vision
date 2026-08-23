package com.drones.vision.api.dto;

import com.drones.vision.perception.domain.model.ReferenceIndexSummary;
import com.drones.vision.perception.domain.model.ReferenceRegion;
import com.drones.vision.perception.domain.model.RegionBounds;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Wire shape for {@link ReferenceRegion} (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.3) — the
 * {@code GET}/{@code POST /api/geo/regions} response element. The stats fields ({@code
 * tileCount}..{@code builtAt}) are {@code null} for a {@code BUILDING}/{@code FAILED} region — see
 * {@link ReferenceRegion#summary()}'s own "non-null iff READY/NEVER_ACCEPT" contract — and omitted
 * from the wire entirely rather than serialized as {@code null} literals.
 *
 * @param regionId         the region's identifier
 * @param name             operator-facing display name
 * @param zoom             the zoom level it was (or is being) built at
 * @param north            bounding box, degrees
 * @param south            bounding box, degrees
 * @param east             bounding box, degrees
 * @param west             bounding box, degrees
 * @param status           {@code BUILDING}, {@code READY}, {@code NEVER_ACCEPT}, or {@code FAILED}
 *                         — the Java enum name verbatim
 * @param tileCount        tiles actually indexed; {@code null} unless built
 * @param neverAcceptCells cells whose calibration refuses outright; {@code null} unless built
 * @param encoderId        which encoder produced the embeddings; {@code null} unless built
 * @param acceptSimilarity self-calibrated accept threshold; {@code null} unless built
 * @param acceptMargin     self-calibrated accept margin; {@code null} unless built
 * @param holdoutRecallAt1 held-out recall@1; {@code null} unless built
 * @param holdoutMedianErrorMeters held-out median localization error, meters; {@code null} unless built
 * @param builtAt          when the build completed; {@code null} unless built
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegionResponse(
        String regionId, String name, int zoom,
        double north, double south, double east, double west,
        String status,
        Integer tileCount, Integer neverAcceptCells, String encoderId,
        Double acceptSimilarity, Double acceptMargin,
        Double holdoutRecallAt1, Double holdoutMedianErrorMeters,
        Instant builtAt) {

    /**
     * Maps a {@link ReferenceRegion} read model to its wire representation.
     *
     * @param region the region to map
     * @return the response body element for {@code region}
     */
    public static RegionResponse from(ReferenceRegion region) {
        RegionBounds bounds = region.bounds();
        ReferenceIndexSummary summary = region.summary();
        return new RegionResponse(
                region.regionId(), region.name(), region.zoom(),
                bounds.north(), bounds.south(), bounds.east(), bounds.west(),
                region.status().name(),
                summary == null ? null : summary.tileCount(),
                summary == null ? null : summary.neverAcceptCells(),
                summary == null ? null : summary.encoderId(),
                summary == null ? null : summary.acceptSimilarity(),
                summary == null ? null : summary.acceptMargin(),
                summary == null ? null : summary.holdoutRecallAt1(),
                summary == null ? null : summary.holdoutMedianErrorMeters(),
                summary == null ? null : summary.builtAt());
    }
}

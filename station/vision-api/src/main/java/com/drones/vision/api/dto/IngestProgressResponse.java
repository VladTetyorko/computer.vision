package com.drones.vision.api.dto;

import com.drones.vision.perception.domain.model.IngestProgress;
import com.drones.vision.perception.domain.model.ReferenceIndexSummary;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Wire shape for {@link IngestProgress} (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.3) — the {@code
 * GET /api/geo/regions/{regionId}/progress} response body.
 *
 * @param regionId the region this progress update is about
 * @param phase    a free-form phase label
 * @param done     units of {@code phase} completed so far
 * @param total    units of {@code phase} in total; {@code 0} when the phase has no countable unit
 * @param state    {@code RUNNING}, {@code SUCCEEDED}, or {@code FAILED} — the Java enum name verbatim
 * @param message  a free-form status/error message; {@code ""} when there is nothing to say
 * @param stats    the built index's stats; present only on the terminal {@code SUCCEEDED} update
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IngestProgressResponse(String regionId, String phase, int done, int total, String state,
                                      String message, RegionStatsResponse stats) {

    /**
     * Maps an {@link IngestProgress} read model to its wire representation.
     *
     * @param progress the progress update to map
     * @return the response body for {@code progress}
     */
    public static IngestProgressResponse from(IngestProgress progress) {
        ReferenceIndexSummary summary = progress.summary();
        return new IngestProgressResponse(progress.regionId(), progress.phase(), progress.done(), progress.total(),
                progress.state().name(), progress.message(),
                summary == null ? null : RegionStatsResponse.from(summary));
    }

    /**
     * The built-index stats subset {@link RegionResponse} also carries, nested here for a {@code
     * SUCCEEDED} progress update — the same field subset, since both describe the same {@link
     * ReferenceIndexSummary}.
     *
     * @param tileCount                tiles actually indexed
     * @param neverAcceptCells         cells whose calibration refuses outright
     * @param encoderId                which encoder produced the embeddings
     * @param acceptSimilarity         self-calibrated accept threshold
     * @param acceptMargin             self-calibrated accept margin
     * @param holdoutRecallAt1         held-out recall@1
     * @param holdoutMedianErrorMeters held-out median localization error, meters
     */
    public record RegionStatsResponse(int tileCount, int neverAcceptCells, String encoderId,
                                       double acceptSimilarity, double acceptMargin,
                                       double holdoutRecallAt1, double holdoutMedianErrorMeters) {

        static RegionStatsResponse from(ReferenceIndexSummary summary) {
            return new RegionStatsResponse(summary.tileCount(), summary.neverAcceptCells(), summary.encoderId(),
                    summary.acceptSimilarity(), summary.acceptMargin(),
                    summary.holdoutRecallAt1(), summary.holdoutMedianErrorMeters());
        }
    }
}

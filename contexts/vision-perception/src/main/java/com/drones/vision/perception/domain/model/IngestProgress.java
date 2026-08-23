package com.drones.vision.perception.domain.model;

/**
 * One update in a region-ingest job's progress stream — mirrors proto's {@code
 * ReferenceIndexProgress} (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.1), carried unmodified from
 * {@link com.drones.vision.perception.domain.port.ReferenceIndexPort#build}'s {@code
 * Flow.Publisher} into {@code DefaultReferenceRegionService}'s in-memory job tracking (D10) and out
 * again as {@code GET /api/geo/regions/{regionId}/progress}.
 *
 * @param regionId the region this progress update is about
 * @param phase    a free-form phase label ({@code "receiving"|"extracting"|"encoding"|"indexing"
 *                 |"calibrating"|"done"}) — not an enum, since cv-service is free to add phases
 *                 without a Java release
 * @param done     units of {@code phase} completed so far; non-negative
 * @param total    units of {@code phase} in total; {@code 0} when the phase has no countable unit;
 *                 non-negative
 * @param state    {@link IngestState#RUNNING} until a terminal update
 * @param message  a free-form status/error message; never {@code null}, {@code ""} when there is
 *                 nothing to say
 * @param summary  present only on the terminal {@link IngestState#SUCCEEDED} update
 */
public record IngestProgress(String regionId, String phase, int done, int total, IngestState state, String message,
                              ReferenceIndexSummary summary) {

    public IngestProgress {
        if (regionId == null || regionId.isBlank()) {
            throw new IllegalArgumentException("IngestProgress regionId must not be blank");
        }
        if (phase == null || phase.isBlank()) {
            throw new IllegalArgumentException("IngestProgress phase must not be blank");
        }
        if (done < 0) {
            throw new IllegalArgumentException("IngestProgress done must not be negative: " + done);
        }
        if (total < 0) {
            throw new IllegalArgumentException("IngestProgress total must not be negative: " + total);
        }
        if (state == null) {
            throw new IllegalArgumentException("IngestProgress state must not be null");
        }
        if (message == null) {
            throw new IllegalArgumentException("IngestProgress message must not be null (use \"\")");
        }
        if (summary != null && state != IngestState.SUCCEEDED) {
            throw new IllegalArgumentException(
                    "IngestProgress summary must be null unless state is SUCCEEDED: state=" + state);
        }
    }
}

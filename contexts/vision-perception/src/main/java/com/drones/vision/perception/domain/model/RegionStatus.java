package com.drones.vision.perception.domain.model;

/**
 * A reference region's lifecycle state, as reported by {@code GET /api/geo/regions}
 * (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.3's {@code RegionResponse#status}). {@code BUILDING}
 * and {@code FAILED} are Java-side facts — an in-flight (or lastly-failed) ingest job tracked in
 * memory only, per D10; {@code READY} and {@code NEVER_ACCEPT} are cv-service facts, derived from
 * {@link ReferenceIndexSummary} once {@code ListRegions} reports the region built.
 */
public enum RegionStatus {
    /** An ingest job is running for this region; no index exists yet. */
    BUILDING,
    /** Built and self-calibrated with at least one usable cell. */
    READY,
    /** Built, but self-calibration produced no usable cell anywhere in the region (§4.2 G-e) — a
     * real, honest outcome, never silently reported as {@link #READY}. */
    NEVER_ACCEPT,
    /** The last ingest attempt for this region ended in failure. */
    FAILED
}

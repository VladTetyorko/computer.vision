package com.drones.vision.perception.domain.model;

/**
 * {@code GET /api/geo/regions}' actual read model (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.3) —
 * the merge {@link com.drones.vision.perception.application.geo.ReferenceRegionService#list} performs
 * between cv-service's built regions ({@link
 * com.drones.vision.perception.domain.port.ReferenceIndexPort#list}) and Java's own in-memory
 * in-flight ingest jobs (D10: no {@code geo_regions} table — an ingest interrupted by a station
 * restart is simply gone).
 *
 * @param regionId the region's identifier
 * @param name     operator-facing display name
 * @param bounds   the region's bounding box
 * @param zoom     the zoom level it was (or is being) built at
 * @param status   {@link RegionStatus#BUILDING}/{@link RegionStatus#FAILED} come from an in-memory
 *                 job; {@link RegionStatus#READY}/{@link RegionStatus#NEVER_ACCEPT} come from {@code
 *                 summary}
 * @param summary  the built index's stats; non-{@code null} iff {@code status} is {@code READY} or
 *                 {@code NEVER_ACCEPT}
 */
public record ReferenceRegion(String regionId, String name, RegionBounds bounds, int zoom, RegionStatus status,
                               ReferenceIndexSummary summary) {

    public ReferenceRegion {
        if (regionId == null || regionId.isBlank()) {
            throw new IllegalArgumentException("ReferenceRegion regionId must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ReferenceRegion name must not be blank");
        }
        if (bounds == null) {
            throw new IllegalArgumentException("ReferenceRegion bounds must not be null");
        }
        if (zoom < 0) {
            throw new IllegalArgumentException("ReferenceRegion zoom must not be negative: " + zoom);
        }
        if (status == null) {
            throw new IllegalArgumentException("ReferenceRegion status must not be null");
        }
        boolean built = status == RegionStatus.READY || status == RegionStatus.NEVER_ACCEPT;
        if (built != (summary != null)) {
            throw new IllegalArgumentException(
                    "ReferenceRegion summary must be non-null iff status is READY/NEVER_ACCEPT: status=" + status
                            + ", summary=" + summary);
        }
    }
}

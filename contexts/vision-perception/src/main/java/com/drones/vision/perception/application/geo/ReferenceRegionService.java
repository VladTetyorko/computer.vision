package com.drones.vision.perception.application.geo;

import com.drones.vision.perception.domain.model.IngestProgress;
import com.drones.vision.perception.domain.model.ReferenceRegion;
import com.drones.vision.perception.domain.model.RegionIngestSpec;

import java.util.List;
import java.util.Optional;

/**
 * Reference-region ingest lifecycle (docs/plans/done/VISUAL-GEO-V2-PLAN.md D10, §3.3) — one
 * interface, one implementation: {@link DefaultReferenceRegionService}. Not a user command in the
 * authorization sense (that is {@code GeoController}'s {@code canAdminister} check, §3.3 O9) — this
 * service takes no {@code VisibilityScope}/actor, since a reference region is shared imagery, not
 * asset- or tenant-scoped data.
 */
public interface ReferenceRegionService {

    /**
     * Starts building one region's index asynchronously and returns immediately with a {@code
     * BUILDING} placeholder — the build itself continues on whatever thread {@link
     * com.drones.vision.perception.domain.port.ReferenceIndexPort#build} drives it from.
     *
     * @param spec the region to build
     * @return the region, in {@code BUILDING} state
     * @throws IllegalArgumentException if {@code spec}'s tile count exceeds the configured ceiling
     * @throws IllegalStateException    if no reference tile provider is configured
     */
    ReferenceRegion ingest(RegionIngestSpec spec);

    /**
     * Every region known — built (cv-service, via {@code ListRegions}) or in-flight (this service's
     * own in-memory job tracking, D10).
     *
     * @return an immutable snapshot
     */
    List<ReferenceRegion> list();

    /**
     * The most recently observed ingest progress for one region.
     *
     * @param regionId the region id
     * @return an in-flight job's latest progress; a synthesized terminal progress if the region is
     *         already built; empty if neither is true
     */
    Optional<IngestProgress> progress(String regionId);

    /**
     * Deletes one region's index and forgets any in-flight job tracked for it. Idempotent.
     *
     * @param regionId the region to delete
     */
    void delete(String regionId);
}

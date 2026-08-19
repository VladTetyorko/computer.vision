package com.drones.vision.perception.domain.port;

import com.drones.vision.perception.domain.model.IngestProgress;
import com.drones.vision.perception.domain.model.ReferenceIndexSummary;
import com.drones.vision.perception.domain.model.RegionIngestSpec;
import com.drones.vision.perception.domain.model.Tile;

import java.util.List;
import java.util.concurrent.Flow;

/**
 * Driven port: cv-service's {@code Geolocation} service, region-ingest half (docs/plans/active/
 * VISUAL-GEO-V2-PLAN.md §3.1's {@code BuildReferenceIndex}/{@code ListRegions}/{@code DeleteRegion}
 * RPCs). cv-service is the single source of truth for which regions exist (D10) — this port never
 * caches or persists anything on the Java side; {@code DefaultReferenceRegionService} tracks only
 * in-flight jobs, in memory.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #build} starts an asynchronous ingest and returns immediately with a live progress
 *       publisher — it does not block until the build finishes. The publisher completes after its
 *       terminal {@link IngestProgress} (a {@link com.drones.vision.perception.domain.model.IngestState#SUCCEEDED}
 *       or {@link com.drones.vision.perception.domain.model.IngestState#FAILED} update), or emits
 *       {@code onError} on a transport failure before any terminal update arrived.</li>
 *   <li>{@link #list} proxies {@code ListRegions} exactly — every currently built region, {@code
 *       READY} or {@code NEVER_ACCEPT} (that distinction is {@link ReferenceIndexSummary#impliedStatus()},
 *       not this port's concern). Never includes an in-flight (still-{@code BUILDING}) region.</li>
 *   <li>{@link #delete} is idempotent — deleting an unknown {@code regionId} is not an error.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Safe to call concurrently for different region ids.
 */
public interface ReferenceIndexPort {

    /**
     * Starts building (or rebuilding) one region's index.
     *
     * @param spec  the region's identity, bounds and zoom
     * @param tiles the tiles to ingest, in whatever order the caller iterates them; may be a lazily
     *              fetched sequence — an implementation must not assume it can be iterated twice
     * @return a live publisher of progress updates for this build
     */
    Flow.Publisher<IngestProgress> build(RegionIngestSpec spec, Iterable<Tile> tiles);

    /**
     * Every region cv-service currently reports as built.
     *
     * @return an immutable snapshot; empty when nothing has ever finished building
     */
    List<ReferenceIndexSummary> list();

    /**
     * Deletes one region's index.
     *
     * @param regionId the region to delete
     */
    void delete(String regionId);
}

package com.drones.vision.perception.domain.port;

/**
 * Driven port: fetches one satellite/aerial reference tile's raw image bytes for a region ingest
 * (docs/plans/done/VISUAL-GEO-V2-PLAN.md §1.3, harvested from the parked branch's {@code
 * adapter-tiles}). Deliberately the narrowest possible seam — {@link
 * com.drones.vision.perception.application.geo.DefaultReferenceRegionService} owns which tiles to
 * fetch (via {@link com.drones.vision.perception.domain.model.TileGrid#cover}); this port only knows
 * how to fetch one.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #supports()} is a cheap, non-blocking readiness check — {@code false} when no tile
 *       provider is configured at all (an operator hasn't set {@code
 *       vision.geo.visual.tiles.url-template}, or the deployment has no imagery provider). Never
 *       throws.</li>
 *   <li>{@link #fetch(int, int, int)} is a blocking network call; an implementation owns its own
 *       retry/rate-limit policy (the harvested {@code TileRateLimiter}) and throws a {@code
 *       RuntimeException} on an unrecoverable failure (provider unreachable, tile not found, quota
 *       exhausted).</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Safe to call concurrently — a region ingest fetches many tiles in parallel, bounded by the
 * provider's own configured concurrency.
 */
public interface ReferenceTileSourcePort {

    /**
     * Whether a tile provider is configured and reachable enough to attempt a fetch.
     *
     * @return {@code true} if {@link #fetch} may be called
     */
    boolean supports();

    /**
     * Fetches one tile's raw image bytes.
     *
     * @param z zoom level
     * @param x tile column
     * @param y tile row
     * @return the tile's raw (JPEG) bytes; never {@code null}
     */
    byte[] fetch(int z, int x, int y);
}

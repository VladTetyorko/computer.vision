package com.drones.vision.perception.domain.model;

/**
 * One fetched reference tile — a {@link TileCoordinate} plus its raw image bytes, exactly what
 * {@code cv_service/geo/pack.py}'s ZIP landing expects (docs/plans/done/VISUAL-GEO-V2-PLAN.md
 * §1.3). Produced lazily by {@link com.drones.vision.perception.application.geo.DefaultReferenceRegionService}
 * as {@link com.drones.vision.perception.domain.port.ReferenceIndexPort#build} consumes its {@code
 * Iterable<Tile>} — never all fetched eagerly into memory (a region may carry up to {@code
 * vision.geo.visual.region.max-tiles} tiles).
 *
 * @param coordinate the tile's {@code z/x/y}
 * @param content    the tile's raw image bytes (JPEG); never {@code null}, may be empty for a
 *                    provider that legitimately returns a blank/transparent tile
 */
public record Tile(TileCoordinate coordinate, byte[] content) {

    public Tile {
        if (coordinate == null) {
            throw new IllegalArgumentException("Tile coordinate must not be null");
        }
        if (content == null) {
            throw new IllegalArgumentException("Tile content must not be null (use an empty array)");
        }
    }
}

package com.drones.vision.perception.domain.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure Web Mercator (slippy-map) tile math: which {@code z/x/y} tiles cover a {@link RegionBounds}
 * at a given zoom (docs/plans/active/VISUAL-GEO-V2-PLAN.md §3.3's tile-count validation and D10's
 * {@code CV_GEO_DATA_DIR/<regionId>/<z>_<x>_<y>.jpg} layout). The standard OSM/Web-Mercator
 * projection formulas are mathematical constants (CLAUDE.md rule 1) — nothing here is a tunable
 * knob.
 *
 * <p>No adapter, no I/O — {@link com.drones.vision.perception.domain.port.ReferenceTileSourcePort}
 * fetches the bytes for whatever coordinates {@link #cover} names.
 */
public final class TileGrid {

    private TileGrid() {
    }

    /**
     * Every tile at {@code zoom} whose square intersects {@code bounds}, in row-major (y then x)
     * order.
     *
     * @param bounds the region's bounding box
     * @param zoom   the zoom level, non-negative
     * @return an immutable, non-empty list of tile coordinates
     */
    public static List<TileCoordinate> cover(RegionBounds bounds, int zoom) {
        Objects.requireNonNull(bounds, "bounds must not be null");
        if (zoom < 0) {
            throw new IllegalArgumentException("TileGrid zoom must not be negative: " + zoom);
        }
        long span = 1L << zoom;
        int minX = clamp(lonToTileX(bounds.west(), zoom), span);
        int maxX = clamp(lonToTileX(bounds.east(), zoom), span);
        int minY = clamp(latToTileY(bounds.north(), zoom), span);
        int maxY = clamp(latToTileY(bounds.south(), zoom), span);

        List<TileCoordinate> tiles = new ArrayList<>();
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                tiles.add(new TileCoordinate(zoom, x, y));
            }
        }
        return List.copyOf(tiles);
    }

    /**
     * How many tiles {@link #cover} would return, without allocating them — the cheap check {@code
     * DefaultReferenceRegionService} runs before fetching a single byte.
     */
    public static long count(RegionBounds bounds, int zoom) {
        Objects.requireNonNull(bounds, "bounds must not be null");
        if (zoom < 0) {
            throw new IllegalArgumentException("TileGrid zoom must not be negative: " + zoom);
        }
        long span = 1L << zoom;
        long minX = clamp(lonToTileX(bounds.west(), zoom), span);
        long maxX = clamp(lonToTileX(bounds.east(), zoom), span);
        long minY = clamp(latToTileY(bounds.north(), zoom), span);
        long maxY = clamp(latToTileY(bounds.south(), zoom), span);
        return (maxX - minX + 1) * (maxY - minY + 1);
    }

    private static int clamp(long value, long span) {
        return (int) Math.max(0, Math.min(span - 1, value));
    }

    private static long lonToTileX(double lonDegrees, int zoom) {
        return (long) Math.floor((lonDegrees + 180.0) / 360.0 * (1L << zoom));
    }

    private static long latToTileY(double latDegrees, int zoom) {
        double latRadians = Math.toRadians(latDegrees);
        return (long) Math.floor(
                (1.0 - Math.log(Math.tan(latRadians) + 1.0 / Math.cos(latRadians)) / Math.PI) / 2.0 * (1L << zoom));
    }
}

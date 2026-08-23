package com.drones.vision.perception.domain.model;

/**
 * A slippy-map (Web Mercator) tile identity — {@code z/x/y}, the same scheme
 * {@code GeoFix#tileId}/{@code RegionResponse}'s tile diagnostics use rendered as {@code "z/x/y"}
 * (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.1/§3.3). Produced by {@link TileGrid#cover}, consumed
 * by {@link com.drones.vision.perception.domain.port.ReferenceTileSourcePort#fetch}.
 *
 * @param z zoom level, non-negative
 * @param x tile column, {@code [0, 2^z)}
 * @param y tile row, {@code [0, 2^z)}
 */
public record TileCoordinate(int z, int x, int y) {

    public TileCoordinate {
        if (z < 0) {
            throw new IllegalArgumentException("TileCoordinate z must not be negative: " + z);
        }
        long span = 1L << z;
        if (x < 0 || x >= span) {
            throw new IllegalArgumentException("TileCoordinate x must be within [0," + span + "): " + x);
        }
        if (y < 0 || y >= span) {
            throw new IllegalArgumentException("TileCoordinate y must be within [0," + span + "): " + y);
        }
    }

    /** {@code "<z>/<x>/<y>"} — the exact diagnostic form the wire contract uses. */
    public String id() {
        return z + "/" + x + "/" + y;
    }
}

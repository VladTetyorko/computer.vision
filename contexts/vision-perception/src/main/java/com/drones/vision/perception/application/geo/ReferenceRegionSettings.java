package com.drones.vision.perception.application.geo;

/**
 * Every knob {@link DefaultReferenceRegionService} needs, sourced from {@code
 * vision.geo.visual.region.*} in the running app (CLAUDE.md rule 1 — no magic numbers in this
 * module).
 *
 * @param maxTiles the tile-count ceiling an ingest is refused above ({@code
 *                 vision.geo.visual.region.max-tiles}); positive
 */
public record ReferenceRegionSettings(int maxTiles) {

    public ReferenceRegionSettings {
        if (maxTiles <= 0) {
            throw new IllegalArgumentException("ReferenceRegionSettings maxTiles must be positive: " + maxTiles);
        }
    }
}

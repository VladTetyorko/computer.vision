package com.drones.vision.api.dto;

import com.drones.vision.perception.domain.model.RegionBounds;
import com.drones.vision.perception.domain.model.RegionIngestSpec;

/**
 * Request body for {@code POST /api/geo/regions} (docs/plans/active/VISUAL-GEO-V2-PLAN.md §3.3,
 * frozen shape: {@code {"name", "north", "south", "east", "west", "zoom"}} — no separate slug
 * field). {@link RegionIngestSpec} needs both a {@code regionId} (a lower-case-kebab identifier,
 * doubling as cv-service's on-disk directory name, D10) and a {@code name} (an operator-facing
 * display name); this request carries only one string, so {@link #toSpec()} uses {@link #name()}
 * for both — the frozen example body's {@code "kyiv-pozniaky"} is already in the required
 * lower-case-kebab form. A caller supplying a {@code name} that is not lower-case-kebab gets {@link
 * RegionIngestSpec}'s own {@link IllegalArgumentException} (→400), same as an out-of-range {@code
 * zoom}.
 *
 * @param name  doubles as the region's id and its display name — see class javadoc
 * @param north bounding box, degrees
 * @param south bounding box, degrees
 * @param east  bounding box, degrees
 * @param west  bounding box, degrees
 * @param zoom  the tile zoom level to ingest at
 */
public record RegionIngestRequest(String name, double north, double south, double east, double west, int zoom) {

    /**
     * @return the application-layer spec this request describes
     * @throws IllegalArgumentException per {@link RegionIngestSpec}'s own validation (blank/non-kebab
     *                                   name, invalid bounds, zoom outside [15,19])
     */
    public RegionIngestSpec toSpec() {
        return new RegionIngestSpec(name, name, new RegionBounds(north, south, east, west), zoom);
    }
}

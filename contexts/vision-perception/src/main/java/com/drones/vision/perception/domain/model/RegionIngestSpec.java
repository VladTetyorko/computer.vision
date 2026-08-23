package com.drones.vision.perception.domain.model;

import java.util.regex.Pattern;

/**
 * A request to build a reference region's index (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.3's
 * {@code POST /api/geo/regions} body). {@code regionId} doubles as cv-service's on-disk directory
 * name ({@code CV_GEO_DATA_DIR/<regionId>/}, D10), hence the lower-case-kebab requirement — the
 * same reasoning {@link com.drones.vision.kernel.CategoryId} already applies to its own slug.
 *
 * @param regionId lower-case-kebab identifier (e.g. {@code "kyiv-pozniaky"}); must not be blank and
 *                 must match {@code [a-z0-9]+(-[a-z0-9]+)*}
 * @param name     an operator-facing display name; must not be blank
 * @param bounds   the region's bounding box; never {@code null}
 * @param zoom     the tile zoom level to ingest at; must fall within {@code [ZOOM_MIN, ZOOM_MAX]} —
 *                 §4.6's measured floor (z16 measured a 0.75-0.96 false-fix rate; z17 is the frozen
 *                 default) and a practical ceiling on tile count, not a business knob, so it is a
 *                 structural constant here rather than a {@code vision.geo.visual.*} setting
 */
public record RegionIngestSpec(String regionId, String name, RegionBounds bounds, int zoom) {

    /** §4.6: z16 measured a 0.75-0.96 false-fix rate and is forbidden; z17 is the frozen default. */
    public static final int ZOOM_MIN = 15;
    /** A practical ceiling — finer zooms explode tile counts for a given bounding box. */
    public static final int ZOOM_MAX = 19;

    private static final Pattern KEBAB_CASE = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    public RegionIngestSpec {
        if (regionId == null || regionId.isBlank()) {
            throw new IllegalArgumentException("RegionIngestSpec regionId must not be blank");
        }
        if (!KEBAB_CASE.matcher(regionId).matches()) {
            throw new IllegalArgumentException("RegionIngestSpec regionId must be lower-case-kebab: " + regionId);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("RegionIngestSpec name must not be blank");
        }
        if (bounds == null) {
            throw new IllegalArgumentException("RegionIngestSpec bounds must not be null");
        }
        if (zoom < ZOOM_MIN || zoom > ZOOM_MAX) {
            throw new IllegalArgumentException(
                    "RegionIngestSpec zoom must be within [" + ZOOM_MIN + "," + ZOOM_MAX + "]: " + zoom);
        }
    }
}

package com.drones.vision.domain.model;

import java.util.List;

/**
 * A named geofence boundary (docs/plans/done/OPS-CORE-PLAN.md §G) — a polygon over {@link GeoPosition}s plus
 * an optional altitude ceiling, evaluated against live telemetry by {@code GeofenceMonitor}
 * (vision-application).
 *
 * <p>{@code polygon} holds at least 3 vertices and is <b>implicitly closed</b>: the edge from the
 * last vertex back to the first is part of the boundary, so the first vertex is never repeated as
 * a last one. What "inside" means depends on {@link #kind()} — see {@link ZoneKind}'s own javadoc
 * — but {@link #contains(GeoPosition)} itself is a plain geometric test, agnostic to that meaning.
 *
 * <p>{@code maxAltitudeMeters} is an independent ceiling checked only while a position is inside
 * the polygon (a sample outside the polygon has no ceiling to violate); {@code null} means no
 * ceiling. Zones are global — every asset is evaluated against every enabled zone; per-group
 * scoping is a later cycle (docs/plans/done/OPS-CORE-PLAN.md §G, U-e), not this one.
 *
 * @param id                typed zone identity
 * @param name              human-readable name; must not be blank
 * @param kind               {@link ZoneKind#KEEP_IN} or {@link ZoneKind#KEEP_OUT}
 * @param polygon            boundary vertices, at least 3, defensively copied; implicitly closed
 *                           (do not repeat the first vertex as the last)
 * @param maxAltitudeMeters  altitude ceiling in meters while inside the polygon, or {@code null}
 *                           for no ceiling; must not be negative if present
 * @param enabled            whether this zone currently participates in breach evaluation
 */
public record GeofenceZone(ZoneId id, String name, ZoneKind kind, List<GeoPosition> polygon,
                            Double maxAltitudeMeters, boolean enabled) {

    /** A polygon needs at least this many vertices to enclose any area at all. */
    private static final int MIN_POLYGON_VERTICES = 3;

    public GeofenceZone {
        if (id == null) {
            throw new IllegalArgumentException("GeofenceZone id must not be null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("GeofenceZone name must not be blank");
        }
        if (kind == null) {
            throw new IllegalArgumentException("GeofenceZone kind must not be null");
        }
        if (polygon == null) {
            throw new IllegalArgumentException("GeofenceZone polygon must not be null");
        }
        if (polygon.size() < MIN_POLYGON_VERTICES) {
            throw new IllegalArgumentException(
                    "GeofenceZone polygon must have at least " + MIN_POLYGON_VERTICES + " vertices: "
                            + polygon.size());
        }
        if (maxAltitudeMeters != null && maxAltitudeMeters < 0) {
            throw new IllegalArgumentException(
                    "GeofenceZone maxAltitudeMeters must not be negative: " + maxAltitudeMeters);
        }
        polygon = List.copyOf(polygon);
    }

    /**
     * Point-in-polygon test via ray casting (the standard even-odd-crossings algorithm): counts how
     * many polygon edges a ray from {@code p} to infinity (here, in the direction of increasing
     * longitude) crosses — an odd count means {@code p} is inside.
     *
     * <p><b>Planar approximation</b>: latitude/longitude are treated as plain Cartesian coordinates
     * (longitude as x, latitude as y), not as points on a sphere. This is accurate at the scale a
     * geofence actually operates at (tens of meters to a few kilometers) — the curvature error over
     * such a small span is negligible — but it is <b>not</b> valid near the poles (where meridians
     * converge, distorting the planar assumption badly) or across the antimeridian (±180°, where
     * longitude wraps to a discontinuity a planar test cannot see, e.g. a polygon meant to span
     * 179°→-179° would instead read as spanning nearly the whole globe the other way). Zones
     * managed by this platform are expected to be authored well away from both; no attempt is made
     * to detect or reject a pathological polygon that does span them.
     *
     * <p>A vertex exactly on the boundary is treated as inside or outside depending on which edges
     * the ray-casting arithmetic happens to cross — the usual, well-known ambiguity of this
     * algorithm at the boundary itself. Every position tested in practice is a live telemetry
     * sample, never a polygon vertex, so this ambiguity has no observable effect here.
     *
     * @param p the position to test
     * @return {@code true} if {@code p} is inside this zone's polygon
     */
    public boolean contains(GeoPosition p) {
        if (p == null) {
            throw new IllegalArgumentException("GeofenceZone.contains position must not be null");
        }
        double x = p.longitude();
        double y = p.latitude();
        boolean inside = false;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            GeoPosition a = polygon.get(i);
            GeoPosition b = polygon.get(j);
            double xi = a.longitude();
            double yi = a.latitude();
            double xj = b.longitude();
            double yj = b.latitude();
            boolean edgeStraddlesY = (yi > y) != (yj > y);
            if (edgeStraddlesY) {
                double xCrossing = xi + (y - yi) * (xj - xi) / (yj - yi);
                if (x < xCrossing) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    /**
     * Returns a copy of this zone with a different enabled flag.
     *
     * @param enabled whether the zone should participate in breach evaluation
     * @return a new {@code GeofenceZone} with {@code enabled} replaced
     */
    public GeofenceZone withEnabled(boolean enabled) {
        return new GeofenceZone(id, name, kind, polygon, maxAltitudeMeters, enabled);
    }

    /**
     * Returns a copy of this zone with edited descriptive fields.
     *
     * <p>Identity and the enabled flag are deliberately not editable here — enabling/disabling a
     * zone is its own act ({@link #withEnabled(boolean)}), independent of redrawing its boundary or
     * renaming it, mirroring how {@link Asset#withDetails} and {@link Device#withDetails} leave
     * lifecycle state untouched.
     *
     * @param name              the replacement name; must not be blank
     * @param kind              the replacement kind
     * @param polygon           the replacement polygon; at least 3 vertices, defensively copied
     * @param maxAltitudeMeters the replacement altitude ceiling, or {@code null} for no ceiling
     * @return a new {@code GeofenceZone} with those fields replaced
     */
    public GeofenceZone withDetails(String name, ZoneKind kind, List<GeoPosition> polygon, Double maxAltitudeMeters) {
        return new GeofenceZone(id, name, kind, polygon, maxAltitudeMeters, enabled);
    }
}

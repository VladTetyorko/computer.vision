package com.drones.vision.perception.domain.model;

/**
 * A reference region's bounding box in WGS-84 degrees (docs/plans/done/VISUAL-GEO-V2-PLAN.md
 * §3.3's {@code RegionResponse} {@code north}/{@code south}/{@code east}/{@code west}). Flat
 * (north/south/east/west) rather than two {@link com.drones.vision.kernel.GeoPosition} corners —
 * a region is a rectangle in lat/lon, never a pair of independently altitude-bearing points, and
 * the flat shape matches the wire contract exactly.
 *
 * @param north degrees, range [-90,90]
 * @param south degrees, range [-90,90]; must be strictly less than {@code north}
 * @param east  degrees, range [-180,180]
 * @param west  degrees, range [-180,180]; must be strictly less than {@code east} (no
 *              antimeridian-crossing regions in this cycle — VISUAL-GEO-V2-PLAN.md §0.3 names no
 *              such case, and none of the harvested tile math handles the wraparound)
 */
public record RegionBounds(double north, double south, double east, double west) {

    public RegionBounds {
        if (Double.isNaN(north) || north < -90.0 || north > 90.0) {
            throw new IllegalArgumentException("RegionBounds north must be within [-90,90]: " + north);
        }
        if (Double.isNaN(south) || south < -90.0 || south > 90.0) {
            throw new IllegalArgumentException("RegionBounds south must be within [-90,90]: " + south);
        }
        if (south >= north) {
            throw new IllegalArgumentException(
                    "RegionBounds south (" + south + ") must be strictly less than north (" + north + ")");
        }
        if (Double.isNaN(east) || east < -180.0 || east > 180.0) {
            throw new IllegalArgumentException("RegionBounds east must be within [-180,180]: " + east);
        }
        if (Double.isNaN(west) || west < -180.0 || west > 180.0) {
            throw new IllegalArgumentException("RegionBounds west must be within [-180,180]: " + west);
        }
        if (west >= east) {
            throw new IllegalArgumentException(
                    "RegionBounds west (" + west + ") must be strictly less than east (" + east + ")");
        }
    }
}

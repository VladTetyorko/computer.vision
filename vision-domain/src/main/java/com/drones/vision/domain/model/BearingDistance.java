package com.drones.vision.domain.model;

/**
 * The great-circle initial bearing and haversine distance from one {@link GeoPosition} to another,
 * as computed by {@link GeoProjection#bearingDistance(GeoPosition, GeoPosition)}
 * (docs/plans/done/TACTICAL-MARKS-PLAN.md §1) — e.g. "how far and in what direction is this mark from the
 * drone / from home".
 *
 * @param bearingDegrees initial bearing, degrees, range [0,360), clockwise from true north
 * @param distanceMeters great-circle distance in meters; never negative
 */
public record BearingDistance(double bearingDegrees, double distanceMeters) {

    public BearingDistance {
        if (Double.isNaN(bearingDegrees) || bearingDegrees < 0.0 || bearingDegrees >= 360.0) {
            throw new IllegalArgumentException(
                    "BearingDistance bearingDegrees must be within [0,360): " + bearingDegrees);
        }
        if (Double.isNaN(distanceMeters) || distanceMeters < 0.0) {
            throw new IllegalArgumentException(
                    "BearingDistance distanceMeters must not be negative: " + distanceMeters);
        }
    }
}

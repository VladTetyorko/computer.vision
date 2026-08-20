package com.drones.vision.perception.domain.model;

/**
 * Restricts a localization session's retrieval to a disc around where the station already believes
 * the aircraft is — proto's {@code GeoPrior} (docs/plans/active/VISUAL-GEO-V2-PLAN.md §3.1).
 *
 * @param latitude     degrees, range [-90,90]
 * @param longitude    degrees, range [-180,180]
 * @param radiusMeters positive
 */
public record GeoPrior(double latitude, double longitude, double radiusMeters) {

    public GeoPrior {
        if (Double.isNaN(latitude) || latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException("GeoPrior latitude must be within [-90,90]: " + latitude);
        }
        if (Double.isNaN(longitude) || longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException("GeoPrior longitude must be within [-180,180]: " + longitude);
        }
        if (Double.isNaN(radiusMeters) || Double.isInfinite(radiusMeters) || radiusMeters <= 0) {
            throw new IllegalArgumentException("GeoPrior radiusMeters must be finite and positive: " + radiusMeters);
        }
    }
}

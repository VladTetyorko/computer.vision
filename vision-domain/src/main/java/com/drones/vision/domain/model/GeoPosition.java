package com.drones.vision.domain.model;

/**
 * A geographic position: latitude/longitude with an optional altitude.
 *
 * <p>{@link Telemetry} remains the raw sample type reported by a device;
 * {@code GeoPosition} is the lighter-weight value derived from it wherever
 * only "where" matters — an asset's last-known position, or a usage's
 * start/last position — rather than the full sensor reading.
 *
 * @param latitude       degrees, range [-90,90]
 * @param longitude      degrees, range [-180,180]
 * @param altitudeMeters meters, nullable
 */
public record GeoPosition(double latitude, double longitude, Double altitudeMeters) {

    public GeoPosition {
        if (Double.isNaN(latitude) || latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException("GeoPosition latitude must be within [-90,90]: " + latitude);
        }
        if (Double.isNaN(longitude) || longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException("GeoPosition longitude must be within [-180,180]: " + longitude);
        }
    }
}

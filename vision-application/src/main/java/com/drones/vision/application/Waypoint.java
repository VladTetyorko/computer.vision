package com.drones.vision.application;

/**
 * One checkpoint on a {@link TelemetryPlan}'s route (docs/CYCLES-PLAN.md §7, CT-a).
 *
 * <p>Deliberately a distinct type from {@code GeoPosition} (vision-domain), even though the shape
 * matches, since the two model different things: {@code GeoPosition} is a derived "where is it
 * now" reading (an asset's last-known position, a usage's start/last position), while
 * {@code Waypoint} is ordered route <em>input</em> that {@link DefaultSimulationService} serializes
 * into {@code SimulatedTelemetrySource}'s (adapter-simulation) {@code route} option string.
 *
 * @param latitude       degrees, range [-90,90]
 * @param longitude      degrees, range [-180,180]
 * @param altitudeMeters meters, nullable — a route may give altitude for some checkpoints and not others
 */
public record Waypoint(double latitude, double longitude, Double altitudeMeters) {

    public Waypoint {
        if (Double.isNaN(latitude) || latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException("Waypoint latitude must be within [-90,90]: " + latitude);
        }
        if (Double.isNaN(longitude) || longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException("Waypoint longitude must be within [-180,180]: " + longitude);
        }
    }
}

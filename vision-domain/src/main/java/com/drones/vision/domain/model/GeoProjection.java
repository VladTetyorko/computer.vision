package com.drones.vision.domain.model;

/**
 * Pure geo-math (docs/plans/done/TACTICAL-MARKS-PLAN.md §1, "New piece #2") — no gimbal orientation or
 * camera-intrinsics data anywhere in the platform, so {@link #project} is an <b>honest estimate</b>
 * (assumed camera depression angle, flat-forward projection along a great circle), not a precise
 * fix. A mark created from it is stamped {@link MarkSource#DETECTION} and stays draggable/editable
 * so an operator can correct it.
 *
 * <p>Reuses {@link GeoPosition} for both input (drone pose) and output (estimated ground point) —
 * no second geo type is invented; a "ground point" is simply a {@code GeoPosition} whose
 * {@code altitudeMeters} is {@code null} (unknown ground elevation).
 *
 * <p>Both methods treat the Earth as a sphere of radius {@link #EARTH_RADIUS_METERS} (the IUGG
 * mean radius) — accurate to well under 1% at the ranges a tactical mark or geofence operates at
 * (meters to a few kilometers), the same trade-off {@link GeofenceZone#contains} documents for its
 * own (planar, not even spherical) approximation. All angles on the public surface are degrees.
 *
 * <p>Not instantiable: a stateless collection of static functions, mirroring how {@link
 * GeofenceZone#contains} is a pure method rather than a dedicated service — this earns no
 * interface (only one implementation, no substitution point; java-clean-code SKILL.md §1).
 */
public final class GeoProjection {

    /**
     * The camera depression angle (degrees below horizontal) assumed when {@link #project} is
     * called without an operator-supplied value. A documented guess, not a measurement — there is
     * no gimbal telemetry to read the real angle from.
     */
    public static final double DEFAULT_DEPRESSION_DEGREES = 45.0;

    /** Mean Earth radius in meters (IUGG), used for both {@link #project} and {@link #bearingDistance}. */
    public static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private GeoProjection() {
    }

    /**
     * Estimates the ground point a drone is looking at, given its pose and an assumed camera
     * depression angle.
     *
     * <p>The slant ground range is {@code altitudeMeters / tan(depressionDegrees)} — e.g. at 45°
     * depression the ground range equals the altitude; as depression rises toward 90° (straight
     * down) the range shrinks toward 0. That range is then projected from {@code drone} along
     * {@code headingDegrees} using the standard spherical destination-point (forward-projection)
     * formula, <b>not</b> {@link GeofenceZone#contains}'s planar approximation.
     *
     * <p>{@code depressionDegrees == 90} is treated as nadir: the aircraft is looking straight
     * down, so the ground point is the drone's own ground position (range 0), independent of
     * {@code headingDegrees}. {@code altitudeMeters == 0} likewise collapses to the drone's own
     * position, for the same reason (zero range) regardless of depression.
     *
     * <p>The returned position's {@code altitudeMeters} is always {@code null} — ground elevation
     * at the projected point is unknown (no terrain model).
     *
     * @param drone            the drone's current position
     * @param headingDegrees   the drone's heading, degrees clockwise from true north; normalized
     *                         modulo 360, so any finite value (including negative or &gt;360) is
     *                         accepted
     * @param altitudeMeters   the drone's altitude above the ground in meters; must not be
     *                         negative
     * @param depressionDegrees the camera's depression angle below horizontal, degrees; must be in
     *                          {@code (0, 90]} — see {@link #DEFAULT_DEPRESSION_DEGREES} for the
     *                          platform's default when no better value is known
     * @return the estimated ground point
     * @throws IllegalArgumentException if {@code drone} is {@code null}, {@code altitudeMeters} is
     *                                   negative or not finite, or {@code depressionDegrees} is not
     *                                   in {@code (0, 90]} or not finite
     */
    public static GeoPosition project(GeoPosition drone, double headingDegrees, double altitudeMeters,
                                       double depressionDegrees) {
        if (drone == null) {
            throw new IllegalArgumentException("GeoProjection.project drone must not be null");
        }
        if (Double.isNaN(altitudeMeters) || Double.isInfinite(altitudeMeters) || altitudeMeters < 0.0) {
            throw new IllegalArgumentException(
                    "GeoProjection.project altitudeMeters must not be negative: " + altitudeMeters);
        }
        if (Double.isNaN(depressionDegrees) || depressionDegrees <= 0.0 || depressionDegrees > 90.0) {
            throw new IllegalArgumentException(
                    "GeoProjection.project depressionDegrees must be within (0,90]: " + depressionDegrees);
        }
        if (Double.isNaN(headingDegrees) || Double.isInfinite(headingDegrees)) {
            throw new IllegalArgumentException(
                    "GeoProjection.project headingDegrees must be finite: " + headingDegrees);
        }

        if (depressionDegrees == 90.0 || altitudeMeters == 0.0) {
            return new GeoPosition(drone.latitude(), drone.longitude(), null);
        }

        double groundRangeMeters = altitudeMeters / Math.tan(Math.toRadians(depressionDegrees));
        return destinationPoint(drone, normalizeDegrees(headingDegrees), groundRangeMeters);
    }

    /**
     * Computes the great-circle initial bearing and haversine distance from one position to
     * another.
     *
     * @param from the reference position (e.g. the drone, or home)
     * @param to   the target position (e.g. a mark)
     * @return the bearing (degrees, clockwise from true north, [0,360)) and distance (meters) from
     *         {@code from} to {@code to}
     * @throws IllegalArgumentException if either argument is {@code null}
     */
    public static BearingDistance bearingDistance(GeoPosition from, GeoPosition to) {
        if (from == null) {
            throw new IllegalArgumentException("GeoProjection.bearingDistance from must not be null");
        }
        if (to == null) {
            throw new IllegalArgumentException("GeoProjection.bearingDistance to must not be null");
        }

        double lat1 = Math.toRadians(from.latitude());
        double lat2 = Math.toRadians(to.latitude());
        double deltaLat = Math.toRadians(to.latitude() - from.latitude());
        double deltaLon = Math.toRadians(to.longitude() - from.longitude());

        double sinHalfLat = Math.sin(deltaLat / 2.0);
        double sinHalfLon = Math.sin(deltaLon / 2.0);
        double a = sinHalfLat * sinHalfLat + Math.cos(lat1) * Math.cos(lat2) * sinHalfLon * sinHalfLon;
        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
        double distanceMeters = EARTH_RADIUS_METERS * c;

        double bearingY = Math.sin(deltaLon) * Math.cos(lat2);
        double bearingX = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(deltaLon);
        double bearingDegrees = normalizeDegrees(Math.toDegrees(Math.atan2(bearingY, bearingX)));

        return new BearingDistance(bearingDegrees, distanceMeters);
    }

    /**
     * Spherical destination-point (forward) projection: the point {@code rangeMeters} away from
     * {@code origin} along the great circle departing at initial bearing {@code headingDegrees}.
     */
    private static GeoPosition destinationPoint(GeoPosition origin, double headingDegrees, double rangeMeters) {
        double angularDistance = rangeMeters / EARTH_RADIUS_METERS;
        double bearing = Math.toRadians(headingDegrees);
        double lat1 = Math.toRadians(origin.latitude());
        double lon1 = Math.toRadians(origin.longitude());

        double sinLat2 = Math.sin(lat1) * Math.cos(angularDistance)
                + Math.cos(lat1) * Math.sin(angularDistance) * Math.cos(bearing);
        sinLat2 = Math.max(-1.0, Math.min(1.0, sinLat2));
        double lat2 = Math.asin(sinLat2);

        double y = Math.sin(bearing) * Math.sin(angularDistance) * Math.cos(lat1);
        double x = Math.cos(angularDistance) - Math.sin(lat1) * sinLat2;
        double lon2 = lon1 + Math.atan2(y, x);

        return new GeoPosition(Math.toDegrees(lat2), normalizeLongitudeDegrees(Math.toDegrees(lon2)), null);
    }

    /** Normalizes an angle in degrees to {@code [0,360)}. */
    private static double normalizeDegrees(double degrees) {
        return ((degrees % 360.0) + 360.0) % 360.0;
    }

    /** Normalizes a longitude in degrees to {@code [-180,180]}, wrapping across the antimeridian. */
    private static double normalizeLongitudeDegrees(double degrees) {
        double wrapped = ((degrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        // The formula above maps 180.0 itself to -180.0; GeoPosition accepts both endpoints, but
        // preferring +180 avoids surprising sign flips for an already-in-range input.
        if (wrapped == -180.0 && degrees > 0.0) {
            return 180.0;
        }
        return wrapped;
    }
}

package com.drones.vision.kernel;

/**
 * Pure geo-math (docs/plans/done/TACTICAL-MARKS-PLAN.md §1, "New piece #2") — {@link #project} is an
 * <b>honest estimate</b> (flat-forward projection along a great circle from an assumed or measured
 * camera depression angle), never a precise fix; no camera-intrinsics data exists anywhere in the
 * platform, so a detection's position within the frame never moves the projected point. A mark
 * created from it is stamped {@link MarkSource#DETECTION} and stays draggable/editable so an operator
 * can correct it.
 *
 * <p>{@link #aimFrom} (docs/plans/active/GEO-POSE-PLAN.md §4.1, wave V1) resolves a {@link
 * CameraAim} from whatever a {@link Telemetry} sample actually reports, preferring a measured gimbal
 * orientation over the airframe heading and the platform's guessed depression default; {@link
 * #project(GeoPosition, CameraAim)} projects from that resolved aim. A device that reports none of
 * the new fields resolves an aim identical to today's assumptions (G6) — the 4-arg {@link #project}
 * overload is unchanged and remains the single source of truth for the math either way.
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
     * The resolved aim of a drone's camera, as produced by {@link #aimFrom} — the single place the
     * precedence rules between a measured gimbal reading and the platform's guessed defaults live
     * (docs/plans/active/GEO-POSE-PLAN.md §4.1, G5). Feeds {@link #project(GeoPosition, CameraAim)}.
     *
     * @param bearingDegrees    the direction the camera is pointed, degrees clockwise from true
     *                          north; finite (any value — {@link #project} normalizes it modulo 360)
     * @param depressionDegrees the camera's depression angle below horizontal, degrees, within
     *                          {@code (0,90]}
     * @param aglMeters         the height, in meters, {@link #project} should treat as the drone's
     *                          height above the ground; never negative. When {@code measured} is
     *                          {@code false} this may be an AMSL altitude standing in for AGL (see
     *                          {@link #aimFrom}) rather than a true ground-height reading
     * @param measured          {@code true} only when both {@code depressionDegrees} came from a
     *                          real gimbal reading and a real AGL reading was available — {@code
     *                          false} whenever any part of the aim was assumed rather than read
     */
    public record CameraAim(double bearingDegrees, double depressionDegrees, double aglMeters, boolean measured) {

        public CameraAim {
            if (Double.isNaN(bearingDegrees) || Double.isInfinite(bearingDegrees)) {
                throw new IllegalArgumentException("CameraAim bearingDegrees must be finite: " + bearingDegrees);
            }
            if (Double.isNaN(depressionDegrees) || depressionDegrees <= 0.0 || depressionDegrees > 90.0) {
                throw new IllegalArgumentException(
                        "CameraAim depressionDegrees must be within (0,90]: " + depressionDegrees);
            }
            if (Double.isNaN(aglMeters) || Double.isInfinite(aglMeters) || aglMeters < 0.0) {
                throw new IllegalArgumentException("CameraAim aglMeters must not be negative: " + aglMeters);
            }
        }
    }

    /**
     * Resolves a {@link CameraAim} from whatever a telemetry sample actually reports, preferring a
     * measured gimbal orientation over an airframe heading and a guessed depression angle
     * (docs/plans/active/GEO-POSE-PLAN.md §4.1). This is the one place the precedence between
     * "measured" and "assumed" inputs is decided; {@link #project(GeoPosition, CameraAim)} does not
     * re-derive it.
     *
     * <p>Precedence, applied independently per output:
     * <ul>
     *   <li><b>{@code bearingDegrees}</b>: {@code telemetry.attitude().gimbalYawDegrees()} — already
     *       earth-frame, so it is usable directly — if present, else {@code
     *       telemetry.headingDegrees()} (the airframe heading), else this method throws: with
     *       neither reading there is nothing to aim along.</li>
     *   <li><b>{@code depressionDegrees}</b>: {@code -telemetry.attitude().gimbalPitchDegrees()} if
     *       that lands within {@code (0,90]}, else {@code fallbackDepressionDegrees}. A gimbal
     *       pitched level ({@code 0}) or upward (negated value {@code <=0}) cannot intersect the
     *       ground ahead of the aircraft, so that reading is discarded in favor of the fallback
     *       rather than fed into the math as-is.</li>
     *   <li><b>{@code aglMeters}</b>: {@code telemetry.aglMeters()} if present, else {@code
     *       telemetry.altitudeMeters()} — the pre-existing behavior, which deliberately carries
     *       today's AMSL-vs-AGL error (docs/plans/active/GEO-POSE-PLAN.md G6/§6) forward as the
     *       honest fallback rather than silently correcting it here.</li>
     *   <li><b>{@code measured}</b>: {@code true} only when the depression angle came from a real
     *       gimbal reading <em>and</em> {@code telemetry.aglMeters()} was present — the two inputs
     *       that determine ground range and origin height. A bearing sourced from airframe heading
     *       rather than gimbal yaw does not by itself make {@code measured} {@code false}: heading is
     *       still a real sensor reading, just not the gimbal's.</li>
     * </ul>
     *
     * @param telemetry                 the telemetry sample to resolve an aim from
     * @param fallbackDepressionDegrees the depression angle to use when no usable gimbal pitch
     *                                  reading is available; must be within {@code (0,90]} (see
     *                                  {@link #DEFAULT_DEPRESSION_DEGREES} for the platform's
     *                                  documented guess)
     * @return the resolved aim
     * @throws IllegalArgumentException if {@code telemetry} is {@code null}; if neither a gimbal yaw
     *                                   nor a heading is available to bear along; if neither {@code
     *                                   aglMeters} nor {@code altitudeMeters} is available to resolve
     *                                   a ground height from; or if the resolved depression/AGL fail
     *                                   {@link CameraAim}'s own range checks (e.g. an out-of-range
     *                                   {@code fallbackDepressionDegrees}, or a negative altitude)
     */
    public static CameraAim aimFrom(Telemetry telemetry, double fallbackDepressionDegrees) {
        if (telemetry == null) {
            throw new IllegalArgumentException("GeoProjection.aimFrom telemetry must not be null");
        }

        Attitude attitude = telemetry.attitude();
        Double gimbalYawDegrees = attitude == null ? null : attitude.gimbalYawDegrees();
        Double bearingDegrees = gimbalYawDegrees != null ? gimbalYawDegrees : telemetry.headingDegrees();
        if (bearingDegrees == null) {
            throw new IllegalArgumentException(
                    "GeoProjection.aimFrom needs a gimbal yaw or a heading to resolve a bearing from");
        }

        Double gimbalPitchDegrees = attitude == null ? null : attitude.gimbalPitchDegrees();
        double gimbalDepressionDegrees = gimbalPitchDegrees == null ? Double.NaN : -gimbalPitchDegrees;
        boolean depressionMeasured =
                gimbalPitchDegrees != null && gimbalDepressionDegrees > 0.0 && gimbalDepressionDegrees <= 90.0;
        double depressionDegrees = depressionMeasured ? gimbalDepressionDegrees : fallbackDepressionDegrees;

        Double measuredAglMeters = telemetry.aglMeters();
        boolean aglMeasured = measuredAglMeters != null;
        Double resolvedAglMeters = aglMeasured ? measuredAglMeters : telemetry.altitudeMeters();
        if (resolvedAglMeters == null) {
            throw new IllegalArgumentException(
                    "GeoProjection.aimFrom needs aglMeters or altitudeMeters to resolve a ground height from");
        }

        return new CameraAim(bearingDegrees, depressionDegrees, resolvedAglMeters, depressionMeasured && aglMeasured);
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
     * Convenience overload of {@link #project(GeoPosition, double, double, double)} that takes a
     * resolved {@link CameraAim} — the shape {@link #aimFrom} produces — instead of separate
     * heading/altitude/depression arguments. The 4-arg overload's signature and behavior are
     * unchanged (docs/plans/active/GEO-POSE-PLAN.md G6); this simply unpacks {@code aim} and
     * delegates, so the projection math has a single source of truth.
     *
     * @param drone the drone's current position
     * @param aim   the resolved camera aim to project from
     * @return the estimated ground point
     * @throws IllegalArgumentException if {@code drone} or {@code aim} is {@code null}
     */
    public static GeoPosition project(GeoPosition drone, CameraAim aim) {
        if (aim == null) {
            throw new IllegalArgumentException("GeoProjection.project aim must not be null");
        }
        return project(drone, aim.bearingDegrees(), aim.aglMeters(), aim.depressionDegrees());
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

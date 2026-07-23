package com.drones.vision.adapter.mavlink;

import java.util.ArrayList;
import java.util.List;

/**
 * A minimal, framework-free flight-route engine for {@link MavlinkFeedTransmitter}: given
 * cumulative distance traveled, returns the interpolated position/heading along a closed,
 * looping path built from waypoints.
 *
 * <p><b>Deliberate duplication, not reuse.</b> {@code adapter-simulation}'s own {@code RoutePlan}
 * already does this — and considerably more (three route modes, altitude interpolation,
 * bounce/once folding) — but adapters must never depend on each other (per {@code CLAUDE.md}),
 * so this class reimplements only the <i>technique</i>: a local equirectangular approximation for
 * distance/bearing between waypoints, adequate at flight-plan scale (the same one {@code
 * RoutePlan} and {@code SimulatedTelemetrySource}'s circular track both use). Only {@code RoutePlan}'s
 * {@code LOOP} behavior is reimplemented here (retrace via a closing end→start leg, then repeat
 * indefinitely) — there is no {@code BOUNCE}/{@code ONCE} equivalent, because a transmitted
 * simulation feed exists to be watched/rehearsed indefinitely, exactly like {@code
 * RtspFeedTransmitter}/{@code MjpegFeedTransmitter}'s own {@code loop=true} TX default.
 */
final class MavlinkRoute {

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    /** One point on the route, in the order given. */
    record Waypoint(double latitude, double longitude, Double altitudeMeters) {
    }

    /** A resolved point along the route: interpolated coordinates and the direction of travel there. */
    record Position(double latitude, double longitude, Double altitudeMeters, double headingDegrees) {
    }

    /** A leg between two consecutive waypoints, pre-computed once at parse time. */
    private record Segment(Waypoint start, Waypoint end, double lengthMeters, double bearingDegrees) {
    }

    /** The closed loop: every waypoint-to-waypoint leg plus a closing end→start leg. */
    private final List<Segment> loopSegments;
    private final double lapLengthMeters;

    private MavlinkRoute(List<Segment> loopSegments, double lapLengthMeters) {
        this.loopSegments = loopSegments;
        this.lapLengthMeters = lapLengthMeters;
    }

    /**
     * @param routeOption {@code lat,lon[,altM];lat,lon[,altM];...}, at least 2 points
     * @return a parsed loop, or {@code null} if {@code routeOption} is absent/blank or malformed
     *         (fewer than 2 points, an unparseable number, a wrongly-shaped point) — callers must
     *         reject the request in that case; unlike {@code adapter-simulation}'s {@code
     *         RoutePlan}, there is no circular-track fallback to lean on here (see class javadoc)
     */
    static MavlinkRoute parse(String routeOption) {
        List<Waypoint> waypoints = parseWaypoints(routeOption);
        if (waypoints == null) {
            return null;
        }
        List<Segment> segments = new ArrayList<>();
        for (int i = 0; i < waypoints.size() - 1; i++) {
            segments.add(buildSegment(waypoints.get(i), waypoints.get(i + 1)));
        }
        segments.add(buildSegment(waypoints.get(waypoints.size() - 1), waypoints.get(0))); // closing leg
        double lapLength = 0.0;
        for (Segment segment : segments) {
            lapLength += segment.lengthMeters();
        }
        return new MavlinkRoute(segments, lapLength);
    }

    private static List<Waypoint> parseWaypoints(String routeOption) {
        if (routeOption == null || routeOption.isBlank()) {
            return null;
        }
        List<Waypoint> waypoints = new ArrayList<>();
        for (String token : routeOption.split(";")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split(",");
            if (parts.length != 2 && parts.length != 3) {
                return null;
            }
            try {
                double latitude = Double.parseDouble(parts[0].trim());
                double longitude = Double.parseDouble(parts[1].trim());
                Double altitude = parts.length == 3 ? Double.parseDouble(parts[2].trim()) : null;
                waypoints.add(new Waypoint(latitude, longitude, altitude));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return waypoints.size() >= 2 ? waypoints : null;
    }

    private static Segment buildSegment(Waypoint start, Waypoint end) {
        double meanLatitudeRadians = Math.toRadians((start.latitude() + end.latitude()) / 2.0);
        double northMeters = Math.toRadians(end.latitude() - start.latitude()) * EARTH_RADIUS_METERS;
        double eastMeters = Math.toRadians(end.longitude() - start.longitude())
                * EARTH_RADIUS_METERS * Math.cos(meanLatitudeRadians);
        double lengthMeters = Math.sqrt(northMeters * northMeters + eastMeters * eastMeters);
        // Geographic bearing (0=north, 90=east, clockwise), same convention as adapter-simulation's RoutePlan.
        double bearingDegrees = (Math.toDegrees(Math.atan2(eastMeters, northMeters)) + 360.0) % 360.0;
        return new Segment(start, end, lengthMeters, bearingDegrees);
    }

    /**
     * @param metersAlongRoute cumulative distance traveled since the route started; negative
     *                         values are clamped to 0
     * @return the interpolated position/heading at that distance, wrapped around the loop
     */
    Position positionAt(double metersAlongRoute) {
        double distance = lapLengthMeters > 0 ? Math.max(0.0, metersAlongRoute) % lapLengthMeters : 0.0;
        double remaining = distance;
        for (int i = 0; i < loopSegments.size(); i++) {
            Segment segment = loopSegments.get(i);
            boolean lastSegment = i == loopSegments.size() - 1;
            if (segment.lengthMeters() <= 0) {
                if (lastSegment) {
                    return interpolate(segment, 0.0);
                }
                continue; // a zero-length leg contributes no distance; try the next one
            }
            if (remaining <= segment.lengthMeters() || lastSegment) {
                return interpolate(segment, Math.min(1.0, remaining / segment.lengthMeters()));
            }
            remaining -= segment.lengthMeters();
        }
        // Unreachable in practice (parse() guarantees >=1 segment); defensive fallback to the start.
        Waypoint first = loopSegments.get(0).start();
        return new Position(first.latitude(), first.longitude(), first.altitudeMeters(), 0.0);
    }

    private static Position interpolate(Segment segment, double fraction) {
        Waypoint start = segment.start();
        Waypoint end = segment.end();
        double latitude = start.latitude() + (end.latitude() - start.latitude()) * fraction;
        double longitude = start.longitude() + (end.longitude() - start.longitude()) * fraction;
        Double altitude = interpolateAltitude(start.altitudeMeters(), end.altitudeMeters(), fraction);
        return new Position(latitude, longitude, altitude, segment.bearingDegrees());
    }

    /** {@code null} unless both endpoints report an altitude — interpolating with only one is meaningless. */
    private static Double interpolateAltitude(Double start, Double end, double fraction) {
        if (start == null || end == null) {
            return null;
        }
        return start + (end - start) * fraction;
    }
}

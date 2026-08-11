package com.drones.vision.adapter.simulation;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses a configurable flight route (docs/main/CYCLES-PLAN.md §7, CT-a) from {@link
 * SimulatedTelemetrySource}'s {@code route}/{@code routeMode} device options and computes a
 * position/heading/altitude at any distance traveled along it.
 *
 * <p>A pure, framework-free, scheduler-free class — {@link #positionAt(double)} is a plain
 * function of "how far have we gone", so the whole engine (parsing, interpolation, and all three
 * {@link RouteMode}s) is unit-testable without a running {@code SimulatedTelemetrySource} or any
 * thread.
 *
 * <p><b>Route format:</b> {@code lat,lon[,altM];lat,lon[,altM];…}, at least 2 points (start →
 * checkpoints → end). Distance and bearing between waypoints use a local equirectangular
 * approximation — the same technique {@code SimulatedTelemetrySource}'s circular track already
 * uses — adequate because flight-plan segments are short enough that this never visibly diverges
 * from a true great-circle calculation (KISS, per docs/main/CYCLES-PLAN.md §7).
 *
 * <p><b>Lenient parsing:</b> {@link #parse} never throws. A missing/malformed route (fewer than 2
 * points, an unparseable number, a wrongly-shaped point) returns {@code null}, signaling the
 * caller to fall back to the circular track — the same lenient-option convention
 * adapter-simulation/MODULE.md already documents for {@code lat}/{@code lon}/{@code width}/{@code
 * height}/{@code fps}. Strict validation of user input lives one layer up, in
 * vision-application/vision-api, where the option strings this class parses are themselves
 * produced from already-validated data.
 */
final class RoutePlan {

    /** How the route repeats once its far end is reached. */
    enum RouteMode {
        /** Retraces from the end back to the start via a closing leg, then repeats. */
        LOOP,
        /** Retraces the same route backwards to the start, then forwards again, back and forth. */
        BOUNCE,
        /** Holds at the end waypoint once reached, still emitting samples there. */
        ONCE
    }

    static final double DEFAULT_SPEED_MPS = 12.0;
    static final RouteMode DEFAULT_MODE = RouteMode.LOOP;

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

    /** {@code ONCE}/{@code BOUNCE} traverse this: the open path, start → … → end, no closing leg. */
    private final List<Segment> pathSegments;
    private final double pathLengthMeters;

    /** {@code LOOP} traverses this instead: {@link #pathSegments} plus a closing end → start leg. */
    private final List<Segment> loopSegments;
    private final double lapLengthMeters;

    private final RouteMode mode;

    private RoutePlan(List<Waypoint> waypoints, RouteMode mode) {
        this.mode = mode;
        this.pathSegments = buildSegments(waypoints, false);
        this.pathLengthMeters = totalLength(pathSegments);
        this.loopSegments = buildSegments(waypoints, true);
        this.lapLengthMeters = totalLength(loopSegments);
    }

    /**
     * @param routeOption {@code SimulatedTelemetrySource}'s {@code route} option, or {@code null}/blank
     * @param modeOption  {@code SimulatedTelemetrySource}'s {@code routeMode} option ({@code loop}
     *                    default/{@code bounce}/{@code once}, case-insensitive), or {@code null}/blank
     * @return a parsed plan, or {@code null} if {@code routeOption} is absent or malformed — callers
     *         must fall back to the circular track in that case, never throw
     */
    static RoutePlan parse(String routeOption, String modeOption) {
        List<Waypoint> waypoints = parseWaypoints(routeOption);
        if (waypoints == null) {
            return null;
        }
        return new RoutePlan(waypoints, parseMode(modeOption));
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

    private static RouteMode parseMode(String modeOption) {
        if (modeOption == null || modeOption.isBlank()) {
            return DEFAULT_MODE;
        }
        for (RouteMode candidate : RouteMode.values()) {
            if (candidate.name().equalsIgnoreCase(modeOption.trim())) {
                return candidate;
            }
        }
        return DEFAULT_MODE; // lenient: an unrecognized mode falls back to the default, never throws
    }

    private static List<Segment> buildSegments(List<Waypoint> waypoints, boolean closeLoop) {
        List<Segment> segments = new ArrayList<>();
        for (int i = 0; i < waypoints.size() - 1; i++) {
            segments.add(buildSegment(waypoints.get(i), waypoints.get(i + 1)));
        }
        if (closeLoop) {
            segments.add(buildSegment(waypoints.get(waypoints.size() - 1), waypoints.get(0)));
        }
        return segments;
    }

    private static Segment buildSegment(Waypoint start, Waypoint end) {
        double meanLatitudeRadians = Math.toRadians((start.latitude() + end.latitude()) / 2.0);
        double northMeters = Math.toRadians(end.latitude() - start.latitude()) * EARTH_RADIUS_METERS;
        double eastMeters = Math.toRadians(end.longitude() - start.longitude())
                * EARTH_RADIUS_METERS * Math.cos(meanLatitudeRadians);
        double lengthMeters = Math.sqrt(northMeters * northMeters + eastMeters * eastMeters);
        // Geographic bearing (0=north, 90=east, clockwise), consistent with the circular track's own convention.
        double bearingDegrees = (Math.toDegrees(Math.atan2(eastMeters, northMeters)) + 360.0) % 360.0;
        return new Segment(start, end, lengthMeters, bearingDegrees);
    }

    private static double totalLength(List<Segment> segments) {
        double total = 0.0;
        for (Segment segment : segments) {
            total += segment.lengthMeters();
        }
        return total;
    }

    /**
     * @param metersAlongRoute cumulative distance traveled since the route started; negative values
     *                         are clamped to 0
     * @return the interpolated position/heading at that distance, folded according to this plan's
     *         {@link RouteMode}
     */
    Position positionAt(double metersAlongRoute) {
        double distance = Math.max(0.0, metersAlongRoute);
        return switch (mode) {
            case ONCE -> forward(Math.min(distance, pathLengthMeters), pathSegments, pathLengthMeters);
            case LOOP -> forward(fold(distance, lapLengthMeters), loopSegments, lapLengthMeters);
            case BOUNCE -> bounce(distance);
        };
    }

    /**
     * Retraces the open path backwards once the far end is reached: distance is folded into one
     * "there and back" period, and the second half is mirrored onto the same forward segments with
     * a heading reversed by 180°, since the physical location at a given fold-back distance is
     * identical either direction but the direction of travel is not.
     */
    private Position bounce(double distance) {
        if (pathLengthMeters <= 0) {
            return forward(0, pathSegments, pathLengthMeters);
        }
        double period = 2 * pathLengthMeters;
        double remainder = fold(distance, period);
        boolean returning = remainder > pathLengthMeters;
        double mirrored = returning ? period - remainder : remainder;
        Position position = forward(mirrored, pathSegments, pathLengthMeters);
        return returning
                ? new Position(position.latitude(), position.longitude(), position.altitudeMeters(),
                        (position.headingDegrees() + 180.0) % 360.0)
                : position;
    }

    private static double fold(double distance, double period) {
        return period > 0 ? distance % period : 0.0;
    }

    /** Locates {@code distance} (already clamped/folded to {@code [0, totalLength]}) along {@code segments}. */
    private static Position forward(double distance, List<Segment> segments, double totalLength) {
        double remaining = Math.max(0.0, Math.min(distance, totalLength));
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            boolean lastSegment = i == segments.size() - 1;
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
        Waypoint first = segments.get(0).start();
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

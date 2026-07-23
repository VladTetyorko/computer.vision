package com.drones.vision.api.dto;

import com.drones.vision.application.RouteMode;
import com.drones.vision.application.SimulationSpec;
import com.drones.vision.application.SimulationTransport;
import com.drones.vision.application.TelemetryPlan;
import com.drones.vision.application.Waypoint;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Request body for {@code POST /api/simulations} — the one-call, zero-hardware simulation entry
 * point (docs/CYCLES-PLAN.md §0-1, §3, §7).
 *
 * @param displayName human-readable name; may be {@code null}/blank, in which case {@code
 *                     SimulationService} derives one from {@code videoPath}'s file name
 * @param videoPath   absolute path to a local video file on the server; must not be blank
 * @param latitude    home-point latitude for the synthetic telemetry track; may be {@code null};
 *                    ignored when {@link #telemetry()} carries a route
 * @param longitude   home-point longitude for the synthetic telemetry track; may be {@code null};
 *                    ignored when {@link #telemetry()} carries a route
 * @param autoStart   whether to start streaming immediately; {@code null}/absent defaults to
 *                     {@code true} — most callers simulating a drone want to watch it right away
 * @param transport   {@code "direct"} (in-process playback), {@code "rtsp"}, or {@code "mjpeg"}
 *                     (both pushed over the wire and ingested back, docs/CYCLES-PLAN.md §3, §5),
 *                     matched case-insensitively; {@code null}/absent defaults to {@code "direct"}
 *                     — today's behavior
 * @param telemetry   an optional configurable flight plan (docs/CYCLES-PLAN.md §7, CT-a) replacing
 *                     the bare circular home-point track with a piecewise-linear route; {@code
 *                     null}/absent keeps today's behavior
 */
public record StartSimulationRequest(String displayName, String videoPath, Double latitude, Double longitude,
                                      Boolean autoStart, String transport, TelemetryRequest telemetry) {

    /**
     * Converts this request into a {@link SimulationSpec}, defaulting {@link #autoStart()} to
     * {@code true} and {@link #transport()} to {@link SimulationTransport#DIRECT} when absent.
     *
     * @return the input for {@code SimulationService#simulate}
     * @throws IllegalArgumentException if {@link #videoPath()} is blank, {@link #transport()}
     *                                   doesn't match a known {@link SimulationTransport} name, or
     *                                   {@link #telemetry()} is present but invalid (see {@link
     *                                   TelemetryRequest#toPlan()})
     */
    public SimulationSpec toSpec() {
        return new SimulationSpec(displayName, videoPath, latitude, longitude, autoStart == null || autoStart,
                parseTransport(transport), telemetry == null ? null : telemetry.toPlan());
    }

    /**
     * Case-insensitive lookup against {@link SimulationTransport} names, {@code null}/blank
     * defaulting to {@link SimulationTransport#DIRECT} — mirrors {@code CapabilityParsing}'s
     * idiom of listing every valid value in the error message for an unrecognized one.
     */
    private static SimulationTransport parseTransport(String raw) {
        if (raw == null || raw.isBlank()) {
            return SimulationTransport.DIRECT;
        }
        for (SimulationTransport candidate : SimulationTransport.values()) {
            if (candidate.name().equalsIgnoreCase(raw)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unknown transport: " + raw + " (valid values: "
                + Arrays.stream(SimulationTransport.values()).map(Enum::name).collect(Collectors.joining(", "))
                + ")");
    }

    /**
     * The wire shape of a {@link TelemetryPlan} (docs/CYCLES-PLAN.md §7, CT-a) — nested here rather
     * than top-level, mirroring {@code CreateAssetRequest.DeviceSpec}'s nested-request convention.
     *
     * @param speedMps  cruise speed in meters/second; {@code null} defers to the adapter's own
     *                  default; must be positive when given
     * @param routeMode {@code "loop"} (default)/{@code "bounce"}/{@code "once"}, matched
     *                  case-insensitively; {@code null}/blank defers to the adapter's own default
     * @param route     start → checkpoints → end; must contain at least 2 points
     */
    public record TelemetryRequest(Double speedMps, String routeMode, List<WaypointRequest> route) {

        /**
         * @return the {@link TelemetryPlan} this request describes
         * @throws IllegalArgumentException if {@link #route()} has fewer than 2 points, any
         *                                   point's coordinates are out of range, {@link
         *                                   #speedMps()} isn't positive, or {@link #routeMode()}
         *                                   doesn't match a known {@link RouteMode} name
         */
        TelemetryPlan toPlan() {
            if (route == null || route.size() < 2) {
                throw new IllegalArgumentException("telemetry.route must contain at least 2 waypoints");
            }
            List<Waypoint> waypoints = route.stream().map(WaypointRequest::toWaypoint).toList();
            return new TelemetryPlan(speedMps, parseRouteMode(routeMode), waypoints);
        }

        /**
         * Case-insensitive lookup against {@link RouteMode} names; {@code null}/blank defers to the
         * adapter's own default ({@code loop}) by returning {@code null} rather than resolving it
         * here, so {@code SimulatedTelemetrySource} (adapter-simulation) stays the single source of
         * truth for that default.
         */
        private static RouteMode parseRouteMode(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            for (RouteMode candidate : RouteMode.values()) {
                if (candidate.name().equalsIgnoreCase(raw)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("Unknown routeMode: " + raw + " (valid values: "
                    + Arrays.stream(RouteMode.values()).map(Enum::name).collect(Collectors.joining(", ")) + ")");
        }
    }

    /**
     * One checkpoint on a {@link TelemetryRequest#route()}.
     *
     * @param latitude       degrees, range [-90,90]
     * @param longitude      degrees, range [-180,180]
     * @param altitudeMeters meters; {@code null} when not given for this checkpoint
     */
    public record WaypointRequest(double latitude, double longitude, Double altitudeMeters) {

        /** @throws IllegalArgumentException if {@link #latitude()}/{@link #longitude()} is out of range */
        Waypoint toWaypoint() {
            return new Waypoint(latitude, longitude, altitudeMeters);
        }
    }
}

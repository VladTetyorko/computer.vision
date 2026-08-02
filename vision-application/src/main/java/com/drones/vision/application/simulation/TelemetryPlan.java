package com.drones.vision.application.simulation;

import java.util.List;

/**
 * A configurable flight plan for a simulated telemetry device (docs/CYCLES-PLAN.md §7, CT-a):
 * speed, loop/bounce/once repeat behavior, and a route of checkpoints — {@link
 * DefaultSimulationService} converts this into {@code SimulatedTelemetrySource}'s (adapter-simulation)
 * {@code route}/{@code speedMps}/{@code routeMode} device options in place of the bare {@code
 * lat}/{@code lon} home-point options, once {@link SimulationSpec#plan()} is non-null.
 *
 * @param speedMps cruise speed in meters/second; {@code null} defers to {@code
 *                 SimulatedTelemetrySource}'s own default; must be positive when given
 * @param mode     how the route repeats once its far end is reached; {@code null} defers to the
 *                 adapter's own default ({@link RouteMode#LOOP})
 * @param route    start → checkpoints → end; {@code null} or at least 2 waypoints — a single point
 *                 can't define a route
 */
public record TelemetryPlan(Double speedMps, RouteMode mode, List<Waypoint> route) {

    public TelemetryPlan {
        if (speedMps != null && speedMps <= 0) {
            throw new IllegalArgumentException("TelemetryPlan speedMps must be positive: " + speedMps);
        }
        if (route != null) {
            if (route.size() < 2) {
                throw new IllegalArgumentException("TelemetryPlan route must contain at least 2 waypoints");
            }
            route = List.copyOf(route);
        }
    }
}

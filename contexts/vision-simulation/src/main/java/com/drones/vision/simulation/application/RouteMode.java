package com.drones.vision.simulation.application;

/**
 * How a {@link TelemetryPlan}'s route repeats once its far end is reached (docs/main/CYCLES-PLAN.md §7,
 * CT-a) — converted by {@link DefaultSimulationService} into the {@code routeMode} device option
 * {@code SimulatedTelemetrySource} (adapter-simulation) parses.
 *
 * <p>Top-level enum rather than nested in {@link TelemetryPlan}, per house style for
 * commands/read models in this package (see {@link SimulationTransport}).
 */
public enum RouteMode {

    /** Retraces from the end back to the start via a closing leg, then repeats — the default. */
    LOOP,

    /** Retraces the same route backwards to the start, then forwards again, back and forth. */
    BOUNCE,

    /** Holds at the end waypoint once reached, still emitting samples there. */
    ONCE
}

package com.drones.vision.application;

/**
 * How a simulated asset's telemetry reaches its {@code TelemetrySourcePort} — the telemetry-transport
 * half of {@link SimulationSpec}, orthogonal to {@link SimulationTransport} (the video-transport
 * half): a simulation can mix any {@link SimulationTransport} with any {@link TelemetryTransport}
 * independently, since video and telemetry are two entirely separate devices on the same simulated
 * asset — there is no illegal combination between the two.
 *
 * <p>Top-level enum rather than nested in {@link SimulationSpec}, mirroring {@link
 * SimulationTransport}'s own top-level-enum convention for commands/read models in this package.
 */
public enum TelemetryTransport {

    /**
     * Today's default: {@link DefaultSimulationService} registers a {@code "sim"}-protocol
     * telemetry device, driven in-process by {@code SimulatedTelemetrySource} (adapter-simulation) —
     * no wire involved.
     */
    SIM,

    /**
     * {@link DefaultSimulationService} starts a MAVLink UDP feed via {@code
     * FeedTransmitterRegistry}/{@code MavlinkFeedTransmitter} (adapter-mavlink) to a loopback port
     * this app allocates for the simulation, then registers a {@code "mavlink"}-protocol telemetry
     * device bound to that same address, so the platform ingests it through the exact same RX path
     * (via {@code MavlinkTelemetrySource}/{@code MavlinkSocketHub}) a real MAVLink vehicle would use
     * — the natural follow-up flagged by adapter-mavlink/MODULE.md's own Gotchas ("not yet wired
     * into SimulationService/SimulationTransport").
     */
    MAVLINK
}

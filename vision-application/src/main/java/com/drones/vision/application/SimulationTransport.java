package com.drones.vision.application;

/**
 * How a simulated asset's video reaches its {@code "file"}/{@code "rtsp"}-protocol
 * {@code VideoSourcePort} — the transport half of {@link SimulationSpec} (docs/CYCLES-PLAN.md §3).
 *
 * <p>Top-level enum rather than nested in {@link SimulationSpec}, per house style for
 * commands/read models in this package.
 */
public enum SimulationTransport {

    /**
     * Today's default: {@link DefaultSimulationService} registers a {@code "file"}-protocol video
     * device that plays the file back in-process — no wire involved (docs/CYCLES-PLAN.md §1b).
     */
    DIRECT,

    /**
     * {@link DefaultSimulationService} pushes the file over RTSP via {@code FeedTransmitterPort}
     * to a real target (e.g. mediamtx), then registers an {@code "rtsp"}-protocol video device
     * pointing at that same target, so the platform ingests it through the exact same RX path
     * real hardware would use — the TX/RX doctrine of docs/CYCLES-PLAN.md §0/§3.
     */
    RTSP
}

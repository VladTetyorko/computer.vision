package com.drones.vision.application.simulation;

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
    RTSP,

    /**
     * {@link DefaultSimulationService} pushes the file over MJPEG (a tiny in-process HTTP {@code
     * multipart/x-mixed-replace} server) via {@code FeedTransmitterPort}, then registers an
     * {@code "mjpeg"}-protocol video device pointing at that same server, so the platform ingests
     * it through the exact same RX path a real MJPEG camera (e.g. an ESP32-CAM) would use — the
     * second protocol under the TX/RX doctrine of docs/CYCLES-PLAN.md §0/§5. Unlike {@link #RTSP},
     * the returned {@code StreamDescriptor} is registered as-is: the mjpeg TX/RX pair has no
     * same-JVM contention requiring an RX-side timeout augmentation (proven by adapter-mjpeg's
     * in-process round-trip integration test).
     */
    MJPEG
}

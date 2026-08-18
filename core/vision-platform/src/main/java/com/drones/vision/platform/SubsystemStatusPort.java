package com.drones.vision.platform;

/**
 * Driven port: report whether one subsystem is currently working, for {@code
 * SystemStatusController}'s {@code GET /api/system/status} (docs/plans/active/SYSTEM-STATUS-PLAN.md
 * §4). An operator-facing honesty seam — UX-DESIGN's §7.2 "honest status over optimistic status"
 * doctrine — not a health-check protocol; there is no ping/pong here, only "what do you know right
 * now about your own subsystem".
 *
 * <p>Every implementation is adapter-side (cv/grpc, drone-link/mavlink, video-output/publish-hls,
 * station/vision-api each provide one — docs/plans/active/SYSTEM-STATUS-PLAN.md §4.2) because the
 * dependency rule forbids one adapter depending on another; this port is the seam that lets {@code
 * vision-app} collect a {@code List<SubsystemStatusPort>} from adapters that otherwise cannot see
 * each other, and lets {@code vision-api}'s controller depend only on {@code vision-platform} rather
 * than on any adapter directly.
 *
 * <h2>Contract</h2>
 * {@link #status()} must never throw for an ordinary "this subsystem is unhealthy" outcome — that
 * case is exactly what {@link Health#DOWN}/{@link Health#DEGRADED} are for. The controller catches an
 * unexpected exception defensively (reporting {@link Health#UNKNOWN}) so one broken provider can
 * never fail the whole endpoint, but a well-behaved implementation should not rely on that: prefer
 * returning {@link Health#UNKNOWN} yourself when a status genuinely cannot be determined.
 *
 * <h2>Threading</h2>
 * May be called concurrently (one {@code GET /api/system/status} per operator poll); implementations
 * must be safe for concurrent use and should return quickly — read cached/observed state, never
 * perform a live network probe from inside this method.
 */
public interface SubsystemStatusPort {

    /**
     * Reports this subsystem's current status.
     *
     * @return the current {@link SubsystemStatus}
     */
    SubsystemStatus status();
}

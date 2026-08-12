package com.drones.vision.domain.model;

/**
 * Worker-reported pull-mode diagnostics (docs/plans/active/MEDIA-SOT-PLAN.md &sect;5.1 fields 16-21, decision
 * D12) — "the complete, honest accounting of a loop that now runs on another machine." Rides on {@link
 * DetectionResult} as a nullable component, {@code null} in push mode: every one of these six fields
 * is zero on a {@code DetectStream} response, mirroring {@link TrackingTelemetry}'s own "one nullable
 * component, not five flat ones" precedent rather than inventing a second idiom for a second gap.
 *
 * <p>M4 (adapter-cv-grpc) could already decode wire fields 16-21, but four of them — {@link
 * #sourceFps()}, {@link #achievedFps()}, {@link #droppedFrames()}, {@link #missedDeadlines()} — had
 * nowhere in the domain to land; the other two ({@link #decodeMillis()}, {@link #captureSkewMillis()})
 * are genuinely new. This record is the carrier M4 was missing, not a new counting scheme: {@link
 * DetectionRate#sourceFps()}/{@link DetectionRate#submittedFps()}/{@link
 * DetectionRate#droppedInFlight()}/{@link DetectionRate#missedDeadlines()} already existed and keep
 * their meaning — only who counts them changes, from the JVM's own sampler to the worker's self-report.
 *
 * @param decodeMillis      local decode cost for the frame this response describes; must not be negative
 * @param sourceFps         measured rate of the pulled stream; must not be negative
 * @param achievedFps       measured rate at which the worker's own loop actually infers; must not be negative
 * @param droppedFrames     cumulative frames the worker's latest-wins decode loop discarded (D8) — the
 *                          pull analogue of the JVM's in-flight drops; must not be negative
 * @param missedDeadlines   cumulative sampler deadlines no frame arrived in time to serve; must not be negative
 * @param captureSkewMillis the worker's own estimate of (local receipt &minus; capture); {@code 0} means
 *                          unknown. Unconstrained in sign — an estimate, not a measured duration, and a
 *                          worker clock running behind the camera's own is exactly the case this field
 *                          exists to surface, not to forbid. This wave only logs it (docs/plans/active/MEDIA-SOT-PLAN.md
 *                          &sect;5.4 stays frozen); it has no read-model home yet
 */
public record PullTelemetry(long decodeMillis, float sourceFps, float achievedFps, long droppedFrames,
                             long missedDeadlines, long captureSkewMillis) {

    public PullTelemetry {
        if (decodeMillis < 0) {
            throw new IllegalArgumentException("PullTelemetry decodeMillis must not be negative: " + decodeMillis);
        }
        if (sourceFps < 0) {
            throw new IllegalArgumentException("PullTelemetry sourceFps must not be negative: " + sourceFps);
        }
        if (achievedFps < 0) {
            throw new IllegalArgumentException("PullTelemetry achievedFps must not be negative: " + achievedFps);
        }
        if (droppedFrames < 0) {
            throw new IllegalArgumentException("PullTelemetry droppedFrames must not be negative: " + droppedFrames);
        }
        if (missedDeadlines < 0) {
            throw new IllegalArgumentException(
                    "PullTelemetry missedDeadlines must not be negative: " + missedDeadlines);
        }
    }
}

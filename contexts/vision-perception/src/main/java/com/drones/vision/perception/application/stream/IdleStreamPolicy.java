package com.drones.vision.perception.application.stream;

import java.time.Duration;

/**
 * When the idle policy stops a stream nobody is watching (docs/plans/active/STREAM-STATE-PLAN.md &sect;2.7).
 *
 * <p>Its own record rather than three more components on {@code StreamPipelineSettings}: that record
 * describes how one pipeline processes frames, this describes whether a stream should exist at all.
 * They also live under different property roots for the same reason ({@code vision.streams.idle.*}
 * vs {@code vision.pipeline.*}).
 *
 * @param enabled       whether idle streams are stopped at all; {@code false} makes
 *                      {@link IdleStreamReaper} inert without removing it from the wiring
 * @param timeout       how long a stream must go with no observed video demand before it is stopped.
 *                      Generous by default: the cost of stopping too eagerly (an operator's video
 *                      disappears) is far worse than the cost of waiting (CPU, recoverable)
 * @param checkInterval how often idleness is re-evaluated. Every tick costs one
 *                      {@code VideoDemandPort} call per running stream, and that port may do network
 *                      I/O, so this is deliberately much coarser than the detection-demand poll
 */
public record IdleStreamPolicy(boolean enabled, Duration timeout, Duration checkInterval) {

    public IdleStreamPolicy {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("IdleStreamPolicy timeout must be positive");
        }
        if (checkInterval == null || checkInterval.isNegative() || checkInterval.isZero()) {
            throw new IllegalArgumentException("IdleStreamPolicy checkInterval must be positive");
        }
        if (checkInterval.compareTo(timeout) > 0) {
            throw new IllegalArgumentException(
                    "IdleStreamPolicy checkInterval must not exceed timeout: the effective grace would be "
                            + "the check interval, not the timeout, making the configured timeout a fiction");
        }
    }

    /** The off switch, spelled once so callers need not invent a placeholder timeout to disable it. */
    public static IdleStreamPolicy disabled() {
        return new IdleStreamPolicy(false, Duration.ofMinutes(10), Duration.ofSeconds(30));
    }
}

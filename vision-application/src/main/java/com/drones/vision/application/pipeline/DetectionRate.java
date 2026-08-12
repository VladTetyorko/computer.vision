package com.drones.vision.application.pipeline;

import java.time.Duration;
import java.util.Objects;

/**
 * Why this stream is detecting at the rate it is — the rate half of the picture {@link
 * PipelineLatency} gives the cost half of (docs/plans/active/CV-RATE-CONTROL-PLAN.md &sect;1).
 *
 * <h2>Why it is a separate record</h2>
 * {@link PipelineLatency#effectiveFps()} already reports the rate boxes <i>arrive</i> at, and the
 * first measurement taken with it (docs/conclusions/CV-RATE-BUDGET.md &sect;3) showed 7.58 against a
 * configured 10 — but it could not say <b>why</b>, because a completion rate cannot distinguish
 * "the source never delivered a frame to sample" from "the sample was thrown away at the in-flight
 * bound". Those two have opposite fixes. This record separates them by counting the sampler's own
 * decisions, so a shortfall names its own cause instead of inviting an argument.
 *
 * <h2>Reading it</h2>
 * {@link #targetFps()} is what the sampler aimed for, {@link #submittedFps()} what it achieved.
 * When they differ, exactly one of the three counters below is non-zero and says which loss it was:
 * a starving source ({@link #missedDeadlines()}), a saturated detector ({@link #droppedInFlight()}),
 * or a CV outage ({@link #droppedOutage()}).
 *
 * @param window           how far back the counters reach
 * @param sourceFps        measured frame arrival rate of the video source; the hard ceiling on any
 *                         achievable detection rate, and {@code 0} before the first measurement
 * @param targetFps        the rate the sampler is currently aiming for — the operator's {@code
 *                         inferenceFps}, raised to {@code followFps} in FOLLOW, and raised further
 *                         by the adaptive loop when the tracked target demands it
 * @param demandFps        the rate the tracked target's motion asked for before any ceiling was
 *                         applied (docs/plans/active/CV-RATE-CONTROL-PLAN.md &sect;2); {@code 0} when
 *                         nothing is tracked. {@code demandFps > targetFps} means the stream is
 *                         capacity-limited, not configuration-limited — the one comparison that
 *                         separates "raise the ceiling" from "shrink the round trip"
 * @param submittedFps     frames actually handed to the detection port per second, over the window
 * @param submitted        frames handed to the detection port in the window
 * @param droppedInFlight  samples discarded because {@code maxInFlightInferences} were outstanding —
 *                         the detector is slower than the requested rate
 * @param droppedOutage    samples withheld during a detection outage's backoff
 * @param missedDeadlines  sample deadlines no frame arrived in time to serve — the source is slower
 *                         than the requested rate, so no amount of detector capacity would help
 */
public record DetectionRate(Duration window, double sourceFps, double targetFps, double demandFps,
                             double submittedFps, long submitted, long droppedInFlight, long droppedOutage,
                             long missedDeadlines) {

    public DetectionRate {
        Objects.requireNonNull(window, "window must not be null");
    }

    /**
     * @return the snapshot of a stream that has sampled nothing yet, still carrying the two figures
     *         that are known before any frame arrives. What a stream reports between start and its
     *         first sample, and immediately after a model re-arm.
     */
    public static DetectionRate empty(Duration window, double sourceFps, double targetFps) {
        return new DetectionRate(window, sourceFps, targetFps, 0.0, 0.0, 0L, 0L, 0L, 0L);
    }

    /** @return sample opportunities that reached the sampler — every deadline a frame did serve. */
    public long due() {
        return submitted + droppedInFlight + droppedOutage;
    }

    /**
     * @return the fraction of served deadlines that were thrown away, in {@code [0,1]}; {@code 0}
     *         when nothing was due. The single number to watch: anything above zero means the
     *         configured rate is not the delivered one.
     */
    public double dropRatio() {
        long due = due();
        return due == 0L ? 0.0 : (double) (droppedInFlight + droppedOutage) / due;
    }
}

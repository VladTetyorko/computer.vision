package com.drones.vision.api.dto;

import com.drones.vision.perception.application.pipeline.DetectionRate;

/**
 * The {@code "rate"} object of {@code GET /api/streams/{streamId}/tracks}
 * (docs/plans/done/CV-RATE-CONTROL-PLAN.md &sect;1) — why this stream is detecting at the rate it is.
 *
 * <p><b>Why it sits beside {@code latency} rather than inside it.</b> {@link
 * PipelineLatencyResponse#effectiveFps()} already reports the rate boxes arrive at, and the first
 * measurement taken with it (docs/conclusions/CV-RATE-BUDGET.md &sect;3) found 7.58 against a configured
 * 10 — but a completion rate cannot say <i>why</i>, because it cannot distinguish a source that
 * never delivered a frame from a sample thrown away at the in-flight bound. Those have opposite
 * fixes. This object counts the sampler's own decisions so the shortfall names its cause.
 *
 * <p><b>Reading the numbers.</b> Compare {@code targetFps} with {@code submittedFps}. When they
 * differ, exactly one of the three counters is non-zero and identifies the loss: a starving source
 * ({@code missedDeadlines}, and {@code sourceFps} will be at or below {@code targetFps}), a
 * saturated detector ({@code droppedInFlight} — raise {@code maxInFlightInferences} or shrink the
 * round trip), or a CV outage ({@code droppedOutage}).
 *
 * @param windowSeconds   how far back the counters reach
 * @param sourceFps       measured frame arrival rate of the video source — the hard ceiling on any
 *                        achievable detection rate
 * @param targetFps       the rate the sampler aimed for: {@code inferenceFps}, {@code followFps} in
 *                        FOLLOW, or higher still when the adaptive loop raised it
 * @param demandFps       what the tracked target's motion asked for before any ceiling applied;
 *                        {@code 0} when nothing is tracked. {@code demandFps > targetFps} means the
 *                        stream is capacity-limited rather than configuration-limited
 * @param submittedFps    frames actually handed to the detector per second
 * @param submitted       frames handed to the detector in the window
 * @param droppedInFlight samples discarded at the {@code maxInFlightInferences} bound
 * @param droppedOutage   samples withheld during a detection outage's backoff
 * @param missedDeadlines sample deadlines no frame arrived in time to serve, since the stream
 *                        started — a running total, unlike the windowed counters above, because a
 *                        starving source produces no events for a window to age out
 * @param dropRatio       the fraction of served deadlines thrown away, in {@code [0,1]}; anything
 *                        above zero means the configured rate is not the delivered one
 * @param transport       which loop counted these figures: {@code "push"} (the JVM's own sampler) or
 *                        {@code "pull"} (a worker's, self-reported) — docs/plans/done/MEDIA-SOT-PLAN.md
 *                        &sect;5.4/&sect;7. Also the reader's cue for which definition {@code
 *                        PipelineLatencyResponse#roundTripMillis*} is using, since {@code latency} and
 *                        {@code rate} are always read together off the same stream
 * @param decodeMillisP50 median local decode cost the worker reported, milliseconds; {@code 0} in
 *                        push mode
 */
public record DetectionRateResponse(long windowSeconds, double sourceFps, double targetFps,
                                     double demandFps, double submittedFps, long submitted,
                                     long droppedInFlight, long droppedOutage, long missedDeadlines,
                                     double dropRatio, String transport, double decodeMillisP50) {

    /** Maps the application-layer read model onto this wire shape. */
    public static DetectionRateResponse from(DetectionRate rate) {
        return new DetectionRateResponse(rate.window().toSeconds(), rate.sourceFps(), rate.targetFps(),
                rate.demandFps(), rate.submittedFps(), rate.submitted(), rate.droppedInFlight(),
                rate.droppedOutage(), rate.missedDeadlines(), rate.dropRatio(), rate.transport(),
                rate.decodeMillisP50());
    }
}

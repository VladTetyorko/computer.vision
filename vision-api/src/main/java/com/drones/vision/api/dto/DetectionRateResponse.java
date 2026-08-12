package com.drones.vision.api.dto;

import com.drones.vision.application.pipeline.DetectionRate;

/**
 * The {@code "rate"} object of {@code GET /api/streams/{streamId}/tracks}
 * (docs/plans/active/CV-RATE-CONTROL-PLAN.md &sect;1) — why this stream is detecting at the rate it is.
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
 * @param targetFps       the rate the sampler aimed for: {@code inferenceFps}, or {@code followFps}
 *                        in FOLLOW
 * @param submittedFps    frames actually handed to the detector per second
 * @param submitted       frames handed to the detector in the window
 * @param droppedInFlight samples discarded at the {@code maxInFlightInferences} bound
 * @param droppedOutage   samples withheld during a detection outage's backoff
 * @param missedDeadlines sample deadlines no frame arrived in time to serve, since the stream
 *                        started — a running total, unlike the windowed counters above, because a
 *                        starving source produces no events for a window to age out
 * @param dropRatio       the fraction of served deadlines thrown away, in {@code [0,1]}; anything
 *                        above zero means the configured rate is not the delivered one
 */
public record DetectionRateResponse(long windowSeconds, double sourceFps, double targetFps,
                                     double submittedFps, long submitted, long droppedInFlight,
                                     long droppedOutage, long missedDeadlines, double dropRatio) {

    /** Maps the application-layer read model onto this wire shape. */
    public static DetectionRateResponse from(DetectionRate rate) {
        return new DetectionRateResponse(rate.window().toSeconds(), rate.sourceFps(), rate.targetFps(),
                rate.submittedFps(), rate.submitted(), rate.droppedInFlight(), rate.droppedOutage(),
                rate.missedDeadlines(), rate.dropRatio());
    }
}

package com.drones.vision.application.pipeline;

import java.time.Duration;
import java.util.Objects;

/**
 * What a detection actually costs in wall-clock time, as opposed to what cv-service reports it spent
 * computing (docs/conclusions/CV-RATE-BUDGET.md &sect;3).
 *
 * <h2>Why this exists</h2>
 * Before it, every latency number in the system was a <i>server-side compute cost</i>: {@code
 * DetectionResult.inferenceLatency()} is cv-service's own {@code inference_millis}, and {@code
 * TrackingTelemetry.trackerLatency()} its {@code tracker_millis}. Neither includes the JPEG encode,
 * either network hop, or the decode — which together dominate on any link that is not localhost. An
 * operator reporting "the box lags the target" could not be answered from anything the system
 * recorded, which made the complaint unfalsifiable.
 *
 * <h2>The two numbers, and why they are two</h2>
 * The age of the box an operator is looking at is <b>not</b> one quantity:
 * <ul>
 *   <li>{@code roundTripMillis*} — submit &rarr; result available. Everything the detection path
 *       adds to a frame we decided to send: encode, both hops, inference, decode, association.</li>
 *   <li>{@code updateIntervalMillisP50} — how long until the <i>next</i> box arrives. Set by the
 *       sample rate, not by the detector, and the reason a box keeps ageing on screen after it
 *       lands.</li>
 * </ul>
 * A box is therefore between {@code roundTrip} and {@code roundTrip + updateInterval} old when
 * looked at. Reporting only the first would understate what the operator sees by up to a full sample
 * interval — which, at the default 10 fps, is the larger of the two terms.
 *
 * @param window                  how far back these figures reach
 * @param samples                 completed detections in the window; 0 means every figure below is 0
 * @param roundTripMillisP50      median submit&rarr;available, milliseconds
 * @param roundTripMillisP95      95th percentile submit&rarr;available, milliseconds
 * @param roundTripMillisMax      worst submit&rarr;available in the window, milliseconds
 * @param updateIntervalMillisP50 median gap between consecutive completions, milliseconds
 * @param effectiveFps            completions per second over the window — the rate boxes actually
 *                                refresh at, which is {@code <=} the configured {@code inferenceFps}
 *                                whenever in-flight bounding or an outage drops samples
 */
public record PipelineLatency(Duration window, long samples, double roundTripMillisP50,
                               double roundTripMillisP95, double roundTripMillisMax,
                               double updateIntervalMillisP50, double effectiveFps) {

    public PipelineLatency {
        Objects.requireNonNull(window, "window must not be null");
    }

    /**
     * @param window the window this stream's latency is collected over
     * @return the snapshot of a window in which nothing has completed yet — every figure zero. What
     *         a stream reports before its first result, and immediately after a model re-arm.
     */
    public static PipelineLatency empty(Duration window) {
        return new PipelineLatency(window, 0L, 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    /**
     * @return the upper bound on how old the displayed box is: a fresh result plus a full wait for
     *         the next one. The single number to quote when asking "how far behind is the overlay?"
     */
    public double worstBoxAgeMillis() {
        return roundTripMillisP95 + updateIntervalMillisP50;
    }
}

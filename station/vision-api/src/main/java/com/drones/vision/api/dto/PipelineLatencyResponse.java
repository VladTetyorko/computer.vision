package com.drones.vision.api.dto;

import com.drones.vision.perception.domain.model.PipelineLatency;

/**
 * The {@code "latency"} object of {@code GET /api/streams/{streamId}/tracks}
 * (docs/conclusions/CV-RATE-BUDGET.md &sect;3) — what a detection costs in wall-clock time, as
 * distinct from the compute time cv-service self-reports.
 *
 * <p><b>Why it sits beside {@code stats} rather than inside it.</b> {@link TrackStatsResponse} is
 * omitted entirely for a stream with tracking off; latency is not, because the detection path still
 * runs and a lagging overlay is exactly the complaint most likely to be raised against such a
 * stream. The two objects are independently present.
 *
 * <p><b>Reading the numbers.</b> {@code roundTripMillis*} is what the pipeline adds to a frame it
 * chose to send — JPEG encode, both network hops, inference, decode. {@code
 * updateIntervalMillisP50} is how long until the next box, set by the sample rate rather than the
 * detector. A displayed box is therefore between one round trip and {@code worstBoxAgeMillis} old;
 * at the default 10 fps the interval, not the inference, is usually the larger term.
 *
 * @param windowSeconds           how far back these figures reach
 * @param samples                 completed detections in the window
 * @param roundTripMillisP50      median submit&rarr;available, milliseconds
 * @param roundTripMillisP95      95th percentile submit&rarr;available, milliseconds
 * @param roundTripMillisMax      worst submit&rarr;available in the window, milliseconds
 * @param updateIntervalMillisP50 median gap between consecutive completions, milliseconds
 * @param effectiveFps            completions per second — the rate boxes actually refresh at, at or
 *                                below the configured {@code inferenceFps}
 * @param worstBoxAgeMillis       {@code roundTripMillisP95 + updateIntervalMillisP50}: the single
 *                                number to quote for "how far behind is the overlay?"
 */
public record PipelineLatencyResponse(long windowSeconds, long samples, double roundTripMillisP50,
                                       double roundTripMillisP95, double roundTripMillisMax,
                                       double updateIntervalMillisP50, double effectiveFps,
                                       double worstBoxAgeMillis) {

    /** Maps the application-layer read model onto this wire shape. */
    public static PipelineLatencyResponse from(PipelineLatency latency) {
        return new PipelineLatencyResponse(latency.window().toSeconds(), latency.samples(),
                latency.roundTripMillisP50(), latency.roundTripMillisP95(), latency.roundTripMillisMax(),
                latency.updateIntervalMillisP50(), latency.effectiveFps(), latency.worstBoxAgeMillis());
    }
}

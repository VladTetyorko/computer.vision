package com.drones.vision.perception.domain.port;

import com.drones.vision.perception.domain.model.CameraAttitude;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.perception.domain.model.VideoFrame;

import java.util.concurrent.CompletionStage;

/**
 * Driven port: run CV inference on a single sampled frame.
 *
 * <p>The reference implementation calls out to the Python CV service over
 * gRPC; an in-JVM (e.g. ONNX) implementation could satisfy the same port
 * without any change to the application layer. {@code DetectionPort} is
 * deliberately single-frame and request/response shaped — batching,
 * streaming, and connection reuse are adapter concerns.
 *
 * <h2>Contract</h2>
 * Runs inference for {@code frame} using the model and threshold in {@code
 * config} and completes with a {@link DetectionResult} referencing the
 * frame by {@code (streamId, sequence, capturedAt)}. Implementations should
 * fail the returned stage (rather than completing with a partial or
 * sentinel result) when inference cannot be performed, so callers can
 * distinguish "no detections" from "inference failed".
 *
 * <h2>Threading &amp; backpressure</h2>
 * Detection is decoupled from the video path precisely so a slow CV service
 * can never stall it: {@link #detect(VideoFrame, PipelineConfig)} returns a
 * {@link CompletionStage} and <b>implementations must be non-blocking</b> —
 * this method must not block the calling thread waiting on the network or
 * on an inference result. The caller (the application-layer pipeline) is
 * responsible for bounding how many calls to this method are in flight at
 * once (see {@link PipelineConfig#maxInFlightInferences()}); this port
 * itself performs no internal queuing and applies no implicit limit — a
 * caller that does not bound concurrency can create unbounded concurrent
 * requests. When the caller's in-flight bound is reached, it skips sampled
 * frames rather than calling this method, so implementations never need to
 * queue work themselves.
 */
public interface DetectionPort {

    /**
     * Runs inference on a single frame.
     *
     * @param frame  the frame to run inference on
     * @param config model, threshold, and sampling configuration
     * @return a stage that completes with the detection result, or completes
     *         exceptionally if inference could not be performed
     */
    CompletionStage<DetectionResult> detect(VideoFrame frame, PipelineConfig config);

    /**
     * Runs inference on a single frame, telling the detector where the camera was pointing.
     *
     * <p>Additive by default rather than a change to the two-argument form above, for the same
     * reason the wire contract grows only by new fields: an implementation that has no use for
     * attitude — every in-memory and test double in this repo — stays correct without being
     * recompiled, and one that does (the gRPC adapter) overrides this method alone.
     *
     * <p>A {@code null} {@code attitude} is the normal state, not a degradation: it is what a
     * stream with no telemetry, no heading, or no configured field of view supplies, and the
     * detector falls back to whatever ego-motion estimation it can do from pixels alone.
     *
     * @param frame    the frame to run inference on
     * @param config   model, threshold, and sampling configuration
     * @param attitude where the camera was pointing at capture, or {@code null} when unknown
     * @return a stage that completes with the detection result, or completes exceptionally if
     *         inference could not be performed
     */
    default CompletionStage<DetectionResult> detect(VideoFrame frame, PipelineConfig config,
                                                     CameraAttitude attitude) {
        return detect(frame, config);
    }
}

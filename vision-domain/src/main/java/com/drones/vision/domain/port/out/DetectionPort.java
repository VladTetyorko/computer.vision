package com.drones.vision.domain.port.out;

import com.drones.vision.domain.model.DetectionResult;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.VideoFrame;

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
}

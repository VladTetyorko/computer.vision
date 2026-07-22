package com.drones.vision.app.devsupport;

import com.drones.vision.domain.model.DetectionResult;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.VideoFrame;
import com.drones.vision.domain.port.out.DetectionPort;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * No-op {@link DetectionPort}: completes immediately with an empty
 * detection result for every frame; dev/Phase-0 fallback so pipelines can
 * run end to end without a CV backend.
 *
 * <p>Replaced by {@code adapter-cv-grpc} (gRPC client to the Python CV
 * service), planned for Phase 2.
 */
public final class NoopDetectionPort implements DetectionPort {

    @Override
    public CompletionStage<DetectionResult> detect(VideoFrame frame, PipelineConfig config) {
        DetectionResult result = new DetectionResult(frame.streamId(), frame.sequence(), frame.capturedAt(),
                List.of(), Duration.ZERO);
        return CompletableFuture.completedFuture(result);
    }
}

package com.drones.vision.application.pipeline;

import com.drones.vision.domain.model.DetectionResult;
import com.drones.vision.domain.port.out.PulledDetectionPort;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

/**
 * Per-stream pull-mode detection collaborators (docs/plans/active/MEDIA-SOT-PLAN.md wave M5, D5/D6) — bundled
 * because they are only ever supplied together (a running stream is either push or pull, never both)
 * and only ever consumed by {@link StreamPipeline}'s pull-mode detection driver: bundling here is what
 * keeps that class's already-wide constructor from growing three more individually-nullable
 * parameters for one capability (java-clean-code &sect;3).
 *
 * <p>{@code null} on {@link StreamPipeline}'s own constructor means push mode, exactly as before this
 * wave; a non-null instance switches that one pipeline to the pull-mode detection driver.
 *
 * @param port      the driven port {@link StreamPipeline#updateConfig} calls {@link
 *                  PulledDetectionPort#reconfigure} on directly — outside {@link #results()}, since
 *                  restating hot config is a control-plane call, not a result to subscribe to
 * @param results   the live publisher of this pull's {@link DetectionResult}s — typically {@link
 *                  SupervisedPublisher}-wrapped by the caller ({@code DefaultStreamService}) for
 *                  reopen-with-backoff, mirroring how a video source's publisher is wrapped before
 *                  reaching this class; opened lazily on {@link StreamPipeline#start()}, never here
 * @param wallClock wall-clock "now" at result receipt, for the pull-mode {@link PipelineLatency}
 *                  redefinition (docs/plans/active/MEDIA-SOT-PLAN.md &sect;7: {@code receivedAt - capturedAt});
 *                  injectable so a test can drive it deterministically instead of {@link Instant#now()}
 */
public record PullDetectionBinding(PulledDetectionPort port, Flow.Publisher<DetectionResult> results,
                                    Supplier<Instant> wallClock) {

    public PullDetectionBinding {
        Objects.requireNonNull(port, "port must not be null");
        Objects.requireNonNull(results, "results must not be null");
        Objects.requireNonNull(wallClock, "wallClock must not be null");
    }
}

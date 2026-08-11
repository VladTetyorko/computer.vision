package com.drones.vision.events.application;

import com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort;
import com.drones.vision.perception.domain.port.DetectionRepositoryPort;
import com.drones.vision.events.domain.port.ReplayFrameExtractionPort;

import java.util.Objects;

/**
 * The three replay-sourced collaborators {@link com.drones.vision.learning.application.DefaultLabelingService#captureFromReplay} needs,
 * bundled into one constructor parameter (docs/plans/done/CV-TRAINING-V2-PLAN.md §4) — the same
 * java-clean-code SKILL.md §3 reasoning {@link com.drones.vision.learning.application.TrainingStores}'s own javadoc gives: each is a
 * genuine, independently-substitutable port (a real usage/detection repository and, once
 * implemented, a real {@link ReplayFrameExtractionPort} adapter all exist or are on their way), so
 * none of the three is speculative — but listing all three individually alongside {@link
 * com.drones.vision.learning.application.TrainingStores}, {@link com.drones.vision.perception.application.stream.StreamService}, {@link com.drones.vision.warehouse.domain.port.AssetRepositoryPort}
 * and {@link com.drones.vision.platform.AuditTrailPort} would push {@link
 * com.drones.vision.learning.application.DefaultLabelingService}'s constructor past the five-parameter ceiling.
 *
 * @param usages     asset usage lookup — resolves the finished usage a replay capture reads from
 * @param detections historical detection query — the source of a replay capture's suggested
 *                   {@code MODEL} annotations
 * @param frames     pulls one decoded frame out of a stream's durable recording at a specific
 *                   instant
 */
public record ReplaySources(AssetUsageRepositoryPort usages, DetectionRepositoryPort detections,
                             ReplayFrameExtractionPort frames) {

    public ReplaySources {
        Objects.requireNonNull(usages, "usages must not be null");
        Objects.requireNonNull(detections, "detections must not be null");
        Objects.requireNonNull(frames, "frames must not be null");
    }
}

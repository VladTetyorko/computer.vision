package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.DetectionResultEntity;
import com.drones.vision.domain.model.DetectionResult;
import com.drones.vision.domain.model.StreamId;

import java.time.Duration;
import java.util.UUID;

/**
 * {@link DetectionResult} &harr; {@link DetectionResultEntity} mapping, extracted from {@code
 * JpaDetectionRepository} (docs/plans/active/LAYERING-REFACTOR-PLAN.md §3/§7 row C).
 *
 * <p>{@link #toEntity} invents a synthetic {@code UUID} id at mapping time — {@link
 * DetectionResult} itself carries no identity (an append-only result, not an aggregate), so there
 * is nothing domain-side to derive a primary key from; the id never surfaces back through the
 * port.
 */
public final class DetectionResultMapper {

    private DetectionResultMapper() {
    }

    public static DetectionResultEntity toEntity(DetectionResult result) {
        return new DetectionResultEntity(UUID.randomUUID(), result.streamId().value(), result.frameSequence(),
                result.capturedAt(), result.detections(), result.inferenceLatency().toNanos());
    }

    public static DetectionResult toDomain(DetectionResultEntity entity) {
        return new DetectionResult(new StreamId(entity.streamId()), entity.frameSequence(), entity.capturedAt(),
                entity.detections(), Duration.ofNanos(entity.inferenceLatencyNanos()));
    }
}

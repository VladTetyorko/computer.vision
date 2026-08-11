package com.drones.vision.perception.domain.model;

import com.drones.vision.kernel.StreamId;
import java.time.Instant;

/**
 * Filter criteria for reading stored detections.
 *
 * <p>Lives in the domain because it is the vocabulary
 * {@link com.drones.vision.events.domain.port.DetectionRepositoryPort} speaks, not a wire type.
 * Every field except {@code limit} is optional: {@code null} means "do not filter on this".
 *
 * @param streamId restrict to one stream, or {@code null} for all
 * @param from     inclusive lower bound on detection time, or {@code null} for unbounded
 * @param to       exclusive upper bound on detection time, or {@code null} for unbounded
 * @param label    restrict to one class label, or {@code null} for all
 * @param limit    maximum results to return; must be positive
 */
public record DetectionQuery(StreamId streamId, Instant from, Instant to, String label, int limit) {

    public DetectionQuery {
        if (limit <= 0) {
            throw new IllegalArgumentException("DetectionQuery limit must be positive: " + limit);
        }
    }
}

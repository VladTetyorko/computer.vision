package com.drones.vision.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The outcome of running inference on one sampled frame.
 *
 * <p>References the source frame by {@code (streamId, frameSequence,
 * capturedAt)} rather than holding the {@link VideoFrame} itself, so
 * results can be persisted and replayed independently of frame lifetime.
 * {@code detections} is defensively copied to an immutable list.
 *
 * @param streamId          stream the source frame belongs to
 * @param frameSequence     sequence number of the source frame; must be non-negative
 * @param capturedAt        capture timestamp of the source frame
 * @param detections        detections found on the frame; defensively copied to an immutable list; may be empty
 * @param inferenceLatency  wall-clock time the inference call took; must not be negative
 */
public record DetectionResult(StreamId streamId, long frameSequence, Instant capturedAt, List<Detection> detections,
                               Duration inferenceLatency) {

    public DetectionResult {
        if (streamId == null) {
            throw new IllegalArgumentException("DetectionResult streamId must not be null");
        }
        if (frameSequence < 0) {
            throw new IllegalArgumentException("DetectionResult frameSequence must not be negative: " + frameSequence);
        }
        if (capturedAt == null) {
            throw new IllegalArgumentException("DetectionResult capturedAt must not be null");
        }
        if (detections == null) {
            throw new IllegalArgumentException("DetectionResult detections must not be null");
        }
        if (inferenceLatency == null) {
            throw new IllegalArgumentException("DetectionResult inferenceLatency must not be null");
        }
        if (inferenceLatency.isNegative()) {
            throw new IllegalArgumentException("DetectionResult inferenceLatency must not be negative: " + inferenceLatency);
        }
        detections = List.copyOf(detections);
    }
}

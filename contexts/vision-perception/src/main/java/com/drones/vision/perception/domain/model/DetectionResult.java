package com.drones.vision.perception.domain.model;

import com.drones.vision.kernel.StreamId;
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
 * <p>{@code tracking} (docs/plans/done/TRACKING-PLAN.md §4.B, docs/extracts/TRACKING-ORCHESTRATION.md §5.2) is
 * {@code null} when tracking was off for this result — the gap fix that lets {@code detectorRan}
 * and friends reach the API layer at all; see {@link TrackingTelemetry}'s own javadoc for why
 * this is one nullable component and not five flat ones.
 *
 * <p>{@code pullTelemetry} (docs/plans/done/MEDIA-SOT-PLAN.md &sect;5.1/D12) is {@code null} in push mode —
 * the same nullable-component idiom {@code tracking} already established for exactly this reason:
 * a result produced by the worker's pull loop carries its own diagnostics, a result produced by a
 * push-mode {@code DetectStream} call has none to carry.
 *
 * @param streamId          stream the source frame belongs to
 * @param frameSequence     sequence number of the source frame; must be non-negative
 * @param capturedAt        capture timestamp of the source frame
 * @param detections        detections found on the frame; defensively copied to an immutable list; may be empty
 * @param inferenceLatency  wall-clock time the inference call took; must not be negative
 * @param tracking          per-frame tracking facts, or {@code null} if tracking was off
 * @param pullTelemetry     worker-reported pull-mode diagnostics, or {@code null} in push mode
 */
public record DetectionResult(StreamId streamId, long frameSequence, Instant capturedAt, List<Detection> detections,
                               Duration inferenceLatency, TrackingTelemetry tracking, PullTelemetry pullTelemetry) {

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

    /**
     * Convenience constructor for a push-mode result — defaults {@link #pullTelemetry()} to {@code
     * null}, the same "N-1-arg convenience ctor" idiom used elsewhere. This was the canonical
     * constructor before docs/plans/done/MEDIA-SOT-PLAN.md wave M5/D12 added {@link #pullTelemetry()}; every
     * pre-existing 6-arg call site compiles unchanged.
     */
    public DetectionResult(StreamId streamId, long frameSequence, Instant capturedAt, List<Detection> detections,
                            Duration inferenceLatency, TrackingTelemetry tracking) {
        this(streamId, frameSequence, capturedAt, detections, inferenceLatency, tracking, null);
    }

    /**
     * Convenience constructor for a result with tracking off — defaults {@link #tracking()} to
     * {@code null}, the same "N-1-arg convenience ctor" idiom used elsewhere. This was the
     * canonical constructor before docs/plans/done/TRACKING-PLAN.md §4.B added {@link #tracking()}; every
     * pre-existing 5-arg call site compiles unchanged.
     */
    public DetectionResult(StreamId streamId, long frameSequence, Instant capturedAt, List<Detection> detections,
                            Duration inferenceLatency) {
        this(streamId, frameSequence, capturedAt, detections, inferenceLatency, null, null);
    }
}

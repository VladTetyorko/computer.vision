package com.drones.vision.adapter.persistence.entity;

import com.drones.vision.perception.domain.model.Detection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JPA row for {@code detection_results} — mirrors {@link com.drones.vision.perception.domain.model.DetectionResult}
 * field-for-field. {@code id} is a synthetic UUID this entity invents at save time (same rationale
 * as {@link TelemetrySampleEntity}: the domain record carries no identity of its own — it is
 * addressed by {@code (streamId, frameSequence, capturedAt)}, per its own javadoc).
 *
 * <p>{@code detections} stores the whole {@code List<Detection>} as one jsonb column via
 * Hibernate's native JSON support (same mechanism as {@code CategoryEntity#attributeHints}/{@code
 * TelemetrySampleEntity#extra}) rather than a normalized child table — {@link Detection} (plus its
 * nested {@code BoundingBox}/{@code ModelRef}) is a plain immutable record tree Jackson serializes
 * natively, and a detection list is only ever read back whole, never queried into by individual
 * field (label-filtering in {@link com.drones.vision.adapter.persistence.JpaDetectionRepository}
 * happens in Java after fetch, exactly like {@code InMemoryDetectionRepository} does), so a join
 * table would add schema without adding any real query capability.
 *
 * <p>{@code inferenceLatencyNanos} stores {@link com.drones.vision.perception.domain.model.DetectionResult#inferenceLatency()}
 * as a plain {@code bigint} of nanoseconds (via {@code Duration#toNanos()}/{@code Duration#ofNanos}
 * in the repository's mapping) rather than relying on Hibernate's implicit {@code Duration} basic
 * type, keeping this entity's field types uniformly plain scalars like every other entity in this
 * module.
 *
 * <p>No FK to any stream/device table (there isn't one — streams are not a persisted aggregate in
 * this schema). {@code (stream_id, captured_at)} is indexed (see {@code V3__history.sql}) for
 * {@code query} and the retention prune query.
 */
@Entity
@Table(name = "detection_results")
public class DetectionResultEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "stream_id", nullable = false)
    private UUID streamId;

    @Column(name = "frame_sequence", nullable = false)
    private long frameSequence;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detections", columnDefinition = "jsonb", nullable = false)
    private List<Detection> detections = new ArrayList<>();

    @Column(name = "inference_latency_nanos", nullable = false)
    private long inferenceLatencyNanos;

    /** JPA only. */
    protected DetectionResultEntity() {
    }

    public DetectionResultEntity(UUID id, UUID streamId, long frameSequence, Instant capturedAt,
                                  List<Detection> detections, long inferenceLatencyNanos) {
        this.id = id;
        this.streamId = streamId;
        this.frameSequence = frameSequence;
        this.capturedAt = capturedAt;
        this.detections = new ArrayList<>(detections);
        this.inferenceLatencyNanos = inferenceLatencyNanos;
    }

    public UUID id() {
        return id;
    }

    public UUID streamId() {
        return streamId;
    }

    public long frameSequence() {
        return frameSequence;
    }

    public Instant capturedAt() {
        return capturedAt;
    }

    public List<Detection> detections() {
        return detections;
    }

    public long inferenceLatencyNanos() {
        return inferenceLatencyNanos;
    }
}

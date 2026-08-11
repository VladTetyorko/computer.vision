package com.drones.vision.adapter.persistence.entity;

import com.drones.vision.domain.model.Annotation;
import com.drones.vision.domain.model.SampleStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JPA row for {@code training_samples} — mirrors {@link
 * com.drones.vision.domain.model.TrainingSample} field-for-field (docs/plans/done/CV-TRAINING-PLAN.md §1,
 * Wave T1/T3); {@link com.drones.vision.adapter.persistence.JpaTrainingSampleRepository} owns
 * the mapping in both directions.
 *
 * <p>{@code id} is the sample's own {@code TrainingSampleId}, not synthetic — a sample mutates
 * over its own review lifecycle (capture seeds it PENDING, labeling replaces its annotations and
 * moves it to LABELED/DISCARDED), the same "upsert not append" shape {@code
 * DetectionEventRepositoryPort} uses, unlike {@code DetectionResultEntity}'s immutable
 * append-only rows. {@code annotations} stores the whole {@code List<Annotation>} as one jsonb
 * column, same mechanism/rationale as {@code DetectionResultEntity#detections} — {@link
 * Annotation} is a plain immutable record Jackson serializes natively, only ever read back whole
 * with the sample. {@code status} reuses the domain {@link SampleStatus} enum directly, same
 * convention as {@code GeofenceZoneEntity#kind}. {@code asset_id}/{@code labeled_by}/{@code
 * labeled_at} are nullable, mirroring the domain record's own nullability. No FK to {@code
 * datasets} or any other table — same "no cross-entity foreign keys" convention as the rest of
 * this schema.
 */
@Entity
@Table(name = "training_samples")
public class TrainingSampleEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "dataset_id", nullable = false)
    private UUID datasetId;

    @Column(name = "stream_id", nullable = false)
    private UUID streamId;

    @Column(name = "asset_id")
    private UUID assetId;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "width", nullable = false)
    private int width;

    @Column(name = "height", nullable = false)
    private int height;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "annotations", columnDefinition = "jsonb", nullable = false)
    private List<Annotation> annotations = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SampleStatus status;

    @Column(name = "labeled_by")
    private UUID labeledBy;

    @Column(name = "labeled_at")
    private Instant labeledAt;

    /** JPA only. */
    protected TrainingSampleEntity() {
    }

    public TrainingSampleEntity(UUID id, UUID datasetId, UUID streamId, UUID assetId, Instant capturedAt,
                                 int width, int height, List<Annotation> annotations, SampleStatus status,
                                 UUID labeledBy, Instant labeledAt) {
        this.id = id;
        this.datasetId = datasetId;
        this.streamId = streamId;
        this.assetId = assetId;
        this.capturedAt = capturedAt;
        this.width = width;
        this.height = height;
        this.annotations = new ArrayList<>(annotations);
        this.status = status;
        this.labeledBy = labeledBy;
        this.labeledAt = labeledAt;
    }

    public UUID id() {
        return id;
    }

    public UUID datasetId() {
        return datasetId;
    }

    public UUID streamId() {
        return streamId;
    }

    public UUID assetId() {
        return assetId;
    }

    public Instant capturedAt() {
        return capturedAt;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public List<Annotation> annotations() {
        return annotations;
    }

    public SampleStatus status() {
        return status;
    }

    public UUID labeledBy() {
        return labeledBy;
    }

    public Instant labeledAt() {
        return labeledAt;
    }
}

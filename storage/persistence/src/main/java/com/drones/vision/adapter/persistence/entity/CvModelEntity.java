package com.drones.vision.adapter.persistence.entity;

import com.drones.vision.learning.domain.model.ModelMetrics;
import com.drones.vision.learning.domain.model.ModelRuntime;
import com.drones.vision.learning.domain.model.ModelStatus;
import com.drones.vision.learning.domain.model.ModelTaskType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JPA row for {@code cv_models} — mirrors {@link com.drones.vision.learning.domain.model.CvModelRecord}
 * field-for-field (docs/plans/active/CV-SETTINGS-PLAN.md §5.3, {@code V30__cv_model_registry.sql}).
 * {@link com.drones.vision.adapter.persistence.mapper.CvModelMapper} owns the mapping both ways.
 *
 * <p>The primary key is the composite ({@code model_id}, {@code version}) via {@link CvModelId},
 * matching {@code CvModelRecord}'s own composite key — multiple versions of the same model id may
 * coexist. {@code taskType}/{@code runtime}/{@code status} reuse the domain enums directly in
 * {@code @Enumerated(EnumType.STRING)} fields, the same convention {@code DatasetEntity#status}
 * follows. {@code metrics} stores the whole {@link ModelMetrics} record as one <b>nullable</b> jsonb
 * column — {@code CvModelRecord}'s own domain deviation: unlike {@code provenance} below, "no
 * metrics reported yet" is a genuinely absent object, not five independently-nullable columns.
 *
 * <p>{@code ModelProvenance} decomposes into five flat, independently-nullable columns ({@code
 * dataset_id}/{@code training_run_id}/{@code base_model}/{@code epochs}/{@code trained_at}) rather
 * than staying one nested jsonb object — the "For W3" note in CV-SETTINGS-CONTEXT.md's W4-domain
 * handoff. No FK to {@code cv_training_runs} — same "no cross-entity foreign keys" convention as
 * the rest of this schema.
 */
@Entity
@Table(name = "cv_models")
@IdClass(CvModelId.class)
public class CvModelEntity {

    @Id
    @Column(name = "model_id", nullable = false)
    private String modelId;

    @Id
    @Column(name = "version", nullable = false)
    private String version;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "open_vocab", nullable = false)
    private boolean openVocab;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "default_label_filter", columnDefinition = "jsonb", nullable = false)
    private List<String> defaultLabelFilter = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 16)
    private ModelTaskType taskType;

    @Enumerated(EnumType.STRING)
    @Column(name = "runtime", nullable = false, length = 16)
    private ModelRuntime runtime;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "classes", columnDefinition = "jsonb", nullable = false)
    private List<String> classes = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ModelStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metrics", columnDefinition = "jsonb")
    private ModelMetrics metrics;

    @Column(name = "dataset_id")
    private UUID datasetId;

    @Column(name = "training_run_id")
    private UUID trainingRunId;

    @Column(name = "base_model")
    private String baseModel;

    @Column(name = "epochs")
    private Integer epochs;

    @Column(name = "trained_at")
    private Instant trainedAt;

    @Column(name = "promoted_by")
    private UUID promotedBy;

    @Column(name = "promoted_at")
    private Instant promotedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** JPA only. */
    protected CvModelEntity() {
    }

    public CvModelEntity(String modelId, String version, String displayName, String kind, boolean openVocab,
                          List<String> defaultLabelFilter, ModelTaskType taskType, ModelRuntime runtime,
                          List<String> classes, ModelStatus status, ModelMetrics metrics, UUID datasetId,
                          UUID trainingRunId, String baseModel, Integer epochs, Instant trainedAt,
                          UUID promotedBy, Instant promotedAt, Instant createdAt) {
        this.modelId = modelId;
        this.version = version;
        this.displayName = displayName;
        this.kind = kind;
        this.openVocab = openVocab;
        this.defaultLabelFilter = new ArrayList<>(defaultLabelFilter);
        this.taskType = taskType;
        this.runtime = runtime;
        this.classes = new ArrayList<>(classes);
        this.status = status;
        this.metrics = metrics;
        this.datasetId = datasetId;
        this.trainingRunId = trainingRunId;
        this.baseModel = baseModel;
        this.epochs = epochs;
        this.trainedAt = trainedAt;
        this.promotedBy = promotedBy;
        this.promotedAt = promotedAt;
        this.createdAt = createdAt;
    }

    public String modelId() {
        return modelId;
    }

    public String version() {
        return version;
    }

    public String displayName() {
        return displayName;
    }

    public String kind() {
        return kind;
    }

    public boolean openVocab() {
        return openVocab;
    }

    public List<String> defaultLabelFilter() {
        return defaultLabelFilter;
    }

    public ModelTaskType taskType() {
        return taskType;
    }

    public ModelRuntime runtime() {
        return runtime;
    }

    public List<String> classes() {
        return classes;
    }

    public ModelStatus status() {
        return status;
    }

    public ModelMetrics metrics() {
        return metrics;
    }

    public UUID datasetId() {
        return datasetId;
    }

    public UUID trainingRunId() {
        return trainingRunId;
    }

    public String baseModel() {
        return baseModel;
    }

    public Integer epochs() {
        return epochs;
    }

    public Instant trainedAt() {
        return trainedAt;
    }

    public UUID promotedBy() {
        return promotedBy;
    }

    public Instant promotedAt() {
        return promotedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }
}

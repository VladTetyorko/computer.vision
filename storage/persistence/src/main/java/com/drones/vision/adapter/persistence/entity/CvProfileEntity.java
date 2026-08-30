package com.drones.vision.adapter.persistence.entity;

import com.drones.vision.perception.domain.model.EventRuleConfig;
import com.drones.vision.perception.domain.model.TrackingConfig;

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
 * JPA row for {@code cv_profiles} — mirrors {@link com.drones.vision.perception.domain.model.CvProfile}
 * field-for-field (docs/plans/active/CV-SETTINGS-PLAN.md §5.3, {@code V29__cv_profiles.sql}). {@link
 * com.drones.vision.adapter.persistence.mapper.CvProfileMapper} owns the mapping both ways.
 *
 * <p>{@code id} is the domain's own {@code CvProfileId}, not synthetic — same choice {@code
 * DatasetEntity}/{@code ControlProfileEntity} make for their own ids, since a browser refers to a
 * profile by id when binding or editing it. {@code modelId}/{@code modelVersion} are two columns,
 * not one — the domain's {@code ModelRef(id, version)} needs both components to reconstruct (its
 * compact constructor requires {@code version} non-blank), which a single {@code model} column
 * (as the plan's own §5.3 column list has it) cannot round-trip; see the migration's own header for
 * this deviation. {@code trackingConfig}/{@code eventRule} store the whole domain record as one
 * jsonb column each via Hibernate's native JSON support (same mechanism as {@code
 * ControlProfileEntity#channelMap}) — both are only ever read back whole, never queried into by
 * individual field. {@code eventRule} is not in §5.3's column list at all; it is added here because
 * {@link com.drones.vision.perception.domain.model.CvProfile#eventRule()} exists and a row without
 * it could not round-trip (see the migration header for the same deviation note).
 *
 * <p>No FK to any other table — same "no cross-entity foreign keys" convention as the rest of this
 * schema; {@code groupId} is a plain nullable UUID column.
 */
@Entity
@Table(name = "cv_profiles")
public class CvProfileEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "built_in", nullable = false)
    private boolean builtIn;

    @Column(name = "group_id")
    private UUID groupId;

    @Column(name = "model_id", nullable = false)
    private String modelId;

    @Column(name = "model_version", nullable = false)
    private String modelVersion;

    @Column(name = "confidence_threshold", nullable = false)
    private double confidenceThreshold;

    @Column(name = "inference_fps", nullable = false)
    private int inferenceFps;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "label_filter", columnDefinition = "jsonb", nullable = false)
    private List<String> labelFilter = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "label_deny_filter", columnDefinition = "jsonb", nullable = false)
    private List<String> labelDenyFilter = new ArrayList<>();

    @Column(name = "detection_enabled", nullable = false)
    private boolean detectionEnabled;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tracking", columnDefinition = "jsonb", nullable = false)
    private TrackingConfig tracking;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_rule", columnDefinition = "jsonb", nullable = false)
    private EventRuleConfig eventRule;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA only. */
    protected CvProfileEntity() {
    }

    public CvProfileEntity(UUID id, String name, String description, boolean builtIn, UUID groupId,
                            String modelId, String modelVersion, double confidenceThreshold, int inferenceFps,
                            List<String> labelFilter, List<String> labelDenyFilter, boolean detectionEnabled,
                            TrackingConfig tracking, EventRuleConfig eventRule, Instant createdAt,
                            Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.builtIn = builtIn;
        this.groupId = groupId;
        this.modelId = modelId;
        this.modelVersion = modelVersion;
        this.confidenceThreshold = confidenceThreshold;
        this.inferenceFps = inferenceFps;
        this.labelFilter = new ArrayList<>(labelFilter);
        this.labelDenyFilter = new ArrayList<>(labelDenyFilter);
        this.detectionEnabled = detectionEnabled;
        this.tracking = tracking;
        this.eventRule = eventRule;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public boolean builtIn() {
        return builtIn;
    }

    public UUID groupId() {
        return groupId;
    }

    public String modelId() {
        return modelId;
    }

    public String modelVersion() {
        return modelVersion;
    }

    public double confidenceThreshold() {
        return confidenceThreshold;
    }

    public int inferenceFps() {
        return inferenceFps;
    }

    public List<String> labelFilter() {
        return labelFilter;
    }

    public List<String> labelDenyFilter() {
        return labelDenyFilter;
    }

    public boolean detectionEnabled() {
        return detectionEnabled;
    }

    public TrackingConfig tracking() {
        return tracking;
    }

    public EventRuleConfig eventRule() {
        return eventRule;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}

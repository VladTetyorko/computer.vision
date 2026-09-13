package com.drones.vision.adapter.persistence.entity;

import com.drones.vision.perception.domain.model.EventRuleConfig;
import com.drones.vision.perception.domain.model.TrackingKnobPatch;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * JPA row for {@code cv_profiles} — mirrors {@link com.drones.vision.perception.domain.model.CvProfile}
 * field-for-field (docs/plans/active/CV-SETTINGS-PLAN.md §5.3, {@code V29__cv_profiles.sql}, widened
 * to a per-knob patch by {@code V36__cv_profile_patch.sql}, wave W7.2). {@link
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
 * <h2>Wave W7.2 — a profile is a patch (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7, decision E22)</h2>
 * Every column below except {@code id}/{@code name}/{@code description}/{@code builtIn}/{@code
 * groupId}/the timestamps is now nullable, {@code NULL} meaning exactly what {@link
 * com.drones.vision.perception.domain.model.CvProfile}'s own now-nullable knobs mean: "inherit this
 * knob from the tier below." {@code modelId}/{@code modelVersion} must be both {@code NULL} or both
 * set — enforced by the migration's own {@code ck_cv_profiles_model_pair} CHECK constraint, not a
 * Java-side check in this class's constructor: Hibernate hydrates a row into an entity by field
 * reflection when reading (see the protected no-arg constructor below), bypassing the public
 * constructor entirely, so a check placed there could only ever catch a row this same build just
 * wrote through {@link com.drones.vision.adapter.persistence.mapper.CvProfileMapper} — never a row
 * written any other way. A database CHECK constraint is the one place every writer, including a
 * future migration or a manual `psql` session, must pass through.
 *
 * <p>{@code tracking} is now typed {@link TrackingKnobPatch} (5 fields), not the 10-field {@code
 * TrackingConfig} it stored before this wave — but the column itself is untouched by the migration
 * (still one {@code jsonb} value, still read back whole). Every pre-W7.2 row's stored JSON is a full
 * {@code TrackingConfig} object whose keys are a strict superset of {@link TrackingKnobPatch}'s five
 * component names ({@code mode}/{@code engineId}/{@code capabilityLevel}/{@code verifyEveryMillis}/
 * {@code followFps}, spelled identically in both types), so no data rewrite is needed — but an
 * unconfigured Jackson {@code JsonMapper} FAILS on an unrecognized property by default, it does not
 * ignore it; "no {@code FAIL_ON_UNKNOWN_PROPERTIES} configured anywhere" turned out to mean "runs
 * Jackson's own strict default," not "tolerant," confirmed the hard way by {@code
 * UnrecognizedPropertyException: Unrecognized property "lock"} against the real V29 rows on this
 * wave's first scoped build. {@code
 * com.drones.vision.adapter.persistence.config.PersistenceUnit#start(javax.sql.DataSource, boolean)}
 * registers a {@code TrackingKnobPatchJsonMixin} (see that class's own javadoc) scoped to exactly this
 * type to make the five extra keys ({@code redetectIouPercent}, {@code maxAgeFrames}, {@code minHits},
 * {@code reupdateMaxGapMillis}, {@code lock}) tolerated on read — verified by a dedicated persistence
 * test, not just reasoned about.
 *
 * <p>{@code intent} is new: {@link com.drones.vision.perception.domain.model.Intent}'s enum name as
 * plain text, parsed back via {@code Intent#valueOf} in the mapper — the same "store an enum's name,
 * validate on read" convention this table already uses one column over in {@code
 * cv_profile_bindings.scope_kind} (parsed via {@code BindingScope#valueOf}), not a dedicated
 * Postgres enum type or a CHECK-constrained value list.
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

    @Column(name = "model_id")
    private String modelId;

    @Column(name = "model_version")
    private String modelVersion;

    @Column(name = "confidence_threshold")
    private Double confidenceThreshold;

    @Column(name = "inference_fps")
    private Integer inferenceFps;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "label_filter", columnDefinition = "jsonb")
    private List<String> labelFilter;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "label_deny_filter", columnDefinition = "jsonb")
    private List<String> labelDenyFilter;

    @Column(name = "detection_enabled")
    private Boolean detectionEnabled;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tracking", columnDefinition = "jsonb")
    private TrackingKnobPatch tracking;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_rule", columnDefinition = "jsonb")
    private EventRuleConfig eventRule;

    @Column(name = "intent")
    private String intent;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA only. */
    protected CvProfileEntity() {
    }

    public CvProfileEntity(UUID id, String name, String description, boolean builtIn, UUID groupId, String modelId,
                            String modelVersion, Double confidenceThreshold, Integer inferenceFps,
                            List<String> labelFilter, List<String> labelDenyFilter, Boolean detectionEnabled,
                            TrackingKnobPatch tracking, EventRuleConfig eventRule, String intent, Instant createdAt,
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
        this.labelFilter = labelFilter == null ? null : List.copyOf(labelFilter);
        this.labelDenyFilter = labelDenyFilter == null ? null : List.copyOf(labelDenyFilter);
        this.detectionEnabled = detectionEnabled;
        this.tracking = tracking;
        this.eventRule = eventRule;
        this.intent = intent;
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

    public Double confidenceThreshold() {
        return confidenceThreshold;
    }

    public Integer inferenceFps() {
        return inferenceFps;
    }

    public List<String> labelFilter() {
        return labelFilter;
    }

    public List<String> labelDenyFilter() {
        return labelDenyFilter;
    }

    public Boolean detectionEnabled() {
        return detectionEnabled;
    }

    public TrackingKnobPatch tracking() {
        return tracking;
    }

    public EventRuleConfig eventRule() {
        return eventRule;
    }

    public String intent() {
        return intent;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}

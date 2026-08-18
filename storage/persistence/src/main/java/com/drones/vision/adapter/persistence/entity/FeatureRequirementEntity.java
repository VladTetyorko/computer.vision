package com.drones.vision.adapter.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA row for {@code feature_requirements} — mirrors {@link
 * com.drones.vision.flight.domain.model.FeatureRequirement} field-for-field (docs/plans/active/DRONE-ONBOARDING-PLAN.md
 * O5, D6: "a new requirement is a migration, never a five-layer edit"); {@link
 * com.drones.vision.adapter.persistence.mapper.FeatureRequirementMapper} owns the mapping in both
 * directions.
 *
 * <p>{@code id} is a synthetic {@code "<firmware>:<featureKey>"} natural key this entity invents —
 * {@code FeatureRequirement} itself carries no id field, but unlike {@code DetectionResultEntity}'s
 * random-UUID precedent (a genuinely identity-less append-only record), a requirement row is
 * reference data addressed by its own natural composite key, so a stable, human-readable id lets
 * the seed migration use {@code ON CONFLICT (id) DO NOTHING} for idempotency — the same role {@code
 * CategoryEntity}'s natural string id plays for {@code categories}.
 *
 * <p>Read-only from the application's perspective: {@code FeatureRequirementRepositoryPort} has no
 * {@code save} method at all — every row here is seed data written by Flyway migrations (see {@code
 * V18__feature_requirements.sql}), never by application code.
 */
@Entity
@Table(name = "feature_requirements")
public class FeatureRequirementEntity {

    @Id
    @Column(name = "id", length = 160, nullable = false)
    private String id;

    @Column(name = "feature_key", length = 64, nullable = false)
    private String featureKey;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "firmware", length = 32, nullable = false)
    private String firmware;

    @Column(name = "required_message_id")
    private Integer requiredMessageId;

    @Column(name = "required_message_name", length = 64)
    private String requiredMessageName;

    @Column(name = "minimum_hz")
    private Double minimumHz;

    @Column(name = "required_parameter_name", length = 64)
    private String requiredParameterName;

    /** JPA only. */
    protected FeatureRequirementEntity() {
    }

    public FeatureRequirementEntity(String id, String featureKey, String label, String firmware,
                                     Integer requiredMessageId, String requiredMessageName, Double minimumHz,
                                     String requiredParameterName) {
        this.id = id;
        this.featureKey = featureKey;
        this.label = label;
        this.firmware = firmware;
        this.requiredMessageId = requiredMessageId;
        this.requiredMessageName = requiredMessageName;
        this.minimumHz = minimumHz;
        this.requiredParameterName = requiredParameterName;
    }

    public String id() {
        return id;
    }

    public String featureKey() {
        return featureKey;
    }

    public String label() {
        return label;
    }

    public String firmware() {
        return firmware;
    }

    public Integer requiredMessageId() {
        return requiredMessageId;
    }

    public String requiredMessageName() {
        return requiredMessageName;
    }

    public Double minimumHz() {
        return minimumHz;
    }

    public String requiredParameterName() {
        return requiredParameterName;
    }
}

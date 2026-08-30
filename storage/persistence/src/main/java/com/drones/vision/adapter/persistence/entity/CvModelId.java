package com.drones.vision.adapter.persistence.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite-key class for {@link CvModelEntity} — the ({@code modelId}, {@code version}) pair that
 * uniquely identifies one catalogue row (docs/plans/active/CV-SETTINGS-PLAN.md §5.3, {@code
 * V30__cv_model_registry.sql}), matching {@link com.drones.vision.learning.domain.model.CvModelRecord}'s
 * own composite key.
 *
 * <p>A plain mutable class with a public no-arg constructor and matching field names, as JPA's
 * {@code @IdClass} contract requires — same shape as {@code AssignmentId}/{@code
 * CvProfileBindingId}.
 */
public class CvModelId implements Serializable {

    private String modelId;
    private String version;

    /** JPA only. */
    public CvModelId() {
    }

    public CvModelId(String modelId, String version) {
        this.modelId = modelId;
        this.version = version;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CvModelId other)) {
            return false;
        }
        return Objects.equals(modelId, other.modelId) && Objects.equals(version, other.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modelId, version);
    }
}

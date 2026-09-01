package com.drones.vision.adapter.persistence.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite-key class for {@link CvProfileBindingEntity} — the ({@code scopeKind}, {@code
 * scopeId}) pair that uniquely identifies one scope's bound profile (docs/plans/active/CV-SETTINGS-PLAN.md
 * §3.1, {@code V29__cv_profiles.sql}).
 *
 * <p>A plain mutable class with a public no-arg constructor and matching field names, as JPA's
 * {@code @IdClass} contract requires (a record cannot satisfy it — no no-arg constructor). Field
 * names {@code scopeKind}/{@code scopeId} match {@link CvProfileBindingEntity}'s {@code @Id} fields
 * — same shape as {@code AssignmentId}.
 */
public class CvProfileBindingId implements Serializable {

    private String scopeKind;
    private String scopeId;

    /** JPA only. */
    public CvProfileBindingId() {
    }

    public CvProfileBindingId(String scopeKind, String scopeId) {
        this.scopeKind = scopeKind;
        this.scopeId = scopeId;
    }

    public String getScopeKind() {
        return scopeKind;
    }

    public void setScopeKind(String scopeKind) {
        this.scopeKind = scopeKind;
    }

    public String getScopeId() {
        return scopeId;
    }

    public void setScopeId(String scopeId) {
        this.scopeId = scopeId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CvProfileBindingId other)) {
            return false;
        }
        return Objects.equals(scopeKind, other.scopeKind) && Objects.equals(scopeId, other.scopeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scopeKind, scopeId);
    }
}

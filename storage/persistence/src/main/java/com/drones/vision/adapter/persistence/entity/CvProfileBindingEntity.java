package com.drones.vision.adapter.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA row for {@code cv_profile_bindings} — attaches one {@link CvProfileEntity} to a scope
 * (docs/plans/active/CV-SETTINGS-PLAN.md §3.1, {@code V29__cv_profiles.sql}). {@link
 * com.drones.vision.adapter.persistence.mapper.CvProfileMapper} owns the mapping both ways.
 *
 * <p>The primary key is the composite ({@code scope_kind}, {@code scope_id}) via {@link
 * CvProfileBindingId} — a scope has at most one bound profile, matching {@link
 * com.drones.vision.perception.domain.model.CvProfileBinding}'s own contract, the same "the pair
 * is the identity" shape {@code AssignmentEntity} uses. {@code scopeId} is a plain {@code String},
 * not a typed/UUID column: {@link com.drones.vision.perception.domain.model.CvProfileBinding}'s own
 * javadoc requires it (a {@code CategoryId} is a kebab-case slug, not a UUID). {@code profileId} is
 * a plain UUID column, no FK — same "no cross-entity foreign keys" convention as the rest of this
 * schema.
 */
@Entity
@Table(name = "cv_profile_bindings")
@IdClass(CvProfileBindingId.class)
public class CvProfileBindingEntity {

    @Id
    @Column(name = "scope_kind", nullable = false)
    private String scopeKind;

    @Id
    @Column(name = "scope_id", nullable = false)
    private String scopeId;

    @Column(name = "profile_id", nullable = false)
    private UUID profileId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** JPA only. */
    protected CvProfileBindingEntity() {
    }

    public CvProfileBindingEntity(String scopeKind, String scopeId, UUID profileId, Instant createdAt) {
        this.scopeKind = scopeKind;
        this.scopeId = scopeId;
        this.profileId = profileId;
        this.createdAt = createdAt;
    }

    public String scopeKind() {
        return scopeKind;
    }

    public String scopeId() {
        return scopeId;
    }

    public UUID profileId() {
        return profileId;
    }

    public Instant createdAt() {
        return createdAt;
    }
}

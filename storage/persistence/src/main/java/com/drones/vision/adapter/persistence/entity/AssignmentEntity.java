package com.drones.vision.adapter.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * JPA row for {@code pilot_assignments} — one pilot&rarr;asset assignment (docs/plans/done/U-SCOPE-PLAN.md,
 * U-e slice 2, feature 2). {@link com.drones.vision.adapter.persistence.JpaAssignmentRepository}
 * owns the mapping.
 *
 * <p>The primary key is the composite ({@code pilot_user_id}, {@code asset_id}) via {@link
 * AssignmentId} — the pair is the identity, there is no synthetic id (an assignment is a plain join
 * row, like the element-collection join tables elsewhere in this schema, not an aggregate with its
 * own identity). The composite PK doubles as the uniqueness constraint that makes {@code assign} an
 * idempotent upsert with no duplicate rows possible. {@code assigned_at} is bookkeeping only, never
 * surfaced through the port.
 *
 * <p>No FK to {@code users}/{@code assets} — the same "no cross-entity foreign keys" convention as
 * every other table here, keeping parity with the in-memory reference repository (which does no
 * referential checks); existence of the pilot/asset is the application layer's concern.
 *
 * <p>{@code role} (docs/plans/active/AUTH-ROLES-PLAN.md §3.4, wave B3; column added by {@code
 * V33__assignment_roles.sql}) stores {@link com.drones.vision.identity.domain.model.AssignmentRole#name()}
 * — a plain {@code VARCHAR}, not jsonb, since it is a single enum value with no nested shape.
 * Defaults to {@code 'PILOT'} at the column level so every row created before this field existed
 * reads back as the seat it always implicitly granted.
 */
@Entity
@Table(name = "pilot_assignments")
@IdClass(AssignmentId.class)
public class AssignmentEntity {

    @Id
    @Column(name = "pilot_user_id", nullable = false)
    private UUID pilotUserId;

    @Id
    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "role", nullable = false, length = 16)
    private String role;

    /** JPA only. */
    protected AssignmentEntity() {
    }

    public AssignmentEntity(UUID pilotUserId, UUID assetId, String role) {
        this.pilotUserId = pilotUserId;
        this.assetId = assetId;
        this.role = role;
    }

    public UUID pilotUserId() {
        return pilotUserId;
    }

    public UUID assetId() {
        return assetId;
    }

    public String role() {
        return role;
    }
}

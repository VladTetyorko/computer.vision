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

    /** JPA only. */
    protected AssignmentEntity() {
    }

    public AssignmentEntity(UUID pilotUserId, UUID assetId) {
        this.pilotUserId = pilotUserId;
        this.assetId = assetId;
    }

    public UUID pilotUserId() {
        return pilotUserId;
    }

    public UUID assetId() {
        return assetId;
    }
}

package com.drones.vision.adapter.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * JPA row for {@code groups} — mirrors {@link com.drones.vision.domain.model.Group} field-for-field
 * (docs/plans/done/U-AUTH-PLAN.md, wave 3); {@link com.drones.vision.adapter.persistence.JpaGroupRepository}
 * owns the mapping in both directions.
 *
 * <p>{@code parentId} is a nullable {@code UUID} (null = root group). No FK to itself or any other
 * table — same "no cross-entity foreign keys" convention as the rest of this schema (see MODULE.md's
 * Conventions); the in-memory reference repository performs no referential checks either, and slice
 * 1 never queries the tree, only stores and lists it.
 */
@Entity
@Table(name = "groups")
public class GroupEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "parent_id")
    private UUID parentId;

    /** JPA only. */
    protected GroupEntity() {
    }

    public GroupEntity(UUID id, String name, UUID parentId) {
        this.id = id;
        this.name = name;
        this.parentId = parentId;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public UUID parentId() {
        return parentId;
    }
}

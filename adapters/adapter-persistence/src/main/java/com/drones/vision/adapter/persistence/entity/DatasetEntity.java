package com.drones.vision.adapter.persistence.entity;

import com.drones.vision.domain.model.DatasetStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JPA row for {@code datasets} — mirrors {@link com.drones.vision.domain.model.Dataset}
 * field-for-field (docs/plans/done/CV-TRAINING-PLAN.md §1, Wave T1/T3); {@link
 * com.drones.vision.adapter.persistence.JpaDatasetRepository} owns the mapping in both
 * directions.
 *
 * <p>{@code id} is the domain's own {@code DatasetId}, not synthetic — a dataset has real
 * identity, the same choice {@code GeofenceZoneEntity}/{@code MarkEntity} make for their own
 * ids. {@code classes} stores the whole {@code List<String>} as one jsonb column via Hibernate's
 * native JSON support — same mechanism/rationale as {@code GeofenceZoneEntity#polygon}: a
 * dataset's ordered class list is only ever read back whole, never queried into by individual
 * class, so a normalized child table would add schema without adding real query capability.
 * {@code ownership} is flattened to {@code owner_id}/{@code group_id}, same choice {@code
 * AssetEntity}/{@code MarkEntity} make for {@code Ownership}. {@code status} reuses the domain
 * {@link DatasetStatus} enum directly in an {@code @Enumerated(EnumType.STRING)} field, same
 * convention as {@code GeofenceZoneEntity#kind}. No FK to any other table — same "no cross-entity
 * foreign keys" convention as the rest of this schema.
 */
@Entity
@Table(name = "datasets")
public class DatasetEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "target_category")
    private String targetCategory;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "classes", columnDefinition = "jsonb", nullable = false)
    private List<String> classes = new ArrayList<>();

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DatasetStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** JPA only. */
    protected DatasetEntity() {
    }

    public DatasetEntity(UUID id, String name, String targetCategory, List<String> classes, UUID ownerId,
                          UUID groupId, DatasetStatus status, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.targetCategory = targetCategory;
        this.classes = new ArrayList<>(classes);
        this.ownerId = ownerId;
        this.groupId = groupId;
        this.status = status;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String targetCategory() {
        return targetCategory;
    }

    public List<String> classes() {
        return classes;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public UUID groupId() {
        return groupId;
    }

    public DatasetStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }
}

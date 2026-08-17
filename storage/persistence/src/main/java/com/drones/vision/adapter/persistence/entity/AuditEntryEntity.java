package com.drones.vision.adapter.persistence.entity;

import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditTargetType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * JPA row for {@code audit_entries} — mirrors {@link com.drones.vision.platform.AuditEntry}
 * field-for-field; {@code AuditEntryMapper} owns the mapping in both directions.
 *
 * <p>{@code id} is the domain's own {@code AuditId} (not synthetic — every entry has real
 * identity, same choice {@link GeofenceZoneEntity}/{@link MarkEntity} make). {@code details}
 * stores the whole {@code Map<String,String>} as one jsonb column via Hibernate's native JSON
 * support — same mechanism/rationale as {@link CategoryEntity#attributeHints}: free-form,
 * per-entry key/value pairs with no fixed schema, only ever read back whole with the entry, never
 * queried into by individual key.
 *
 * <p>{@code action}/{@code targetType} reuse the domain {@link AuditAction}/{@link
 * AuditTargetType} enums directly in {@code @Enumerated(EnumType.STRING)} fields — the same
 * "domain enums reused directly in entity fields" convention {@code GeofenceZoneEntity#kind}
 * already follows.
 *
 * <p>No FK to any other table (not {@code actor_id} to {@code users}, not {@code target_id} to
 * anything) — same "no cross-entity foreign keys" convention as the rest of this schema. This
 * matters more than usual here: an audit entry must stay resolvable even after its target is
 * hard-deleted or its actor's account is removed, so a referential constraint would be actively
 * wrong, not just inconsistent with the in-memory reference repository.
 */
@Entity
@Table(name = "audit_entries")
public class AuditEntryEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 16)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 16)
    private AuditTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private String targetId;

    @Column(name = "summary", nullable = false, columnDefinition = "text")
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "jsonb", nullable = false)
    private Map<String, String> details = Map.of();

    /** JPA only. */
    protected AuditEntryEntity() {
    }

    public AuditEntryEntity(UUID id, Instant occurredAt, UUID actorId, AuditAction action,
                             AuditTargetType targetType, String targetId, String summary,
                             Map<String, String> details) {
        this.id = id;
        this.occurredAt = occurredAt;
        this.actorId = actorId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.summary = summary;
        this.details = Map.copyOf(details);
    }

    public UUID id() {
        return id;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public UUID actorId() {
        return actorId;
    }

    public AuditAction action() {
        return action;
    }

    public AuditTargetType targetType() {
        return targetType;
    }

    public String targetId() {
        return targetId;
    }

    public String summary() {
        return summary;
    }

    public Map<String, String> details() {
        return details;
    }
}

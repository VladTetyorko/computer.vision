package com.drones.vision.adapter.persistence.entity;

import com.drones.vision.platform.EventType;

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
 * JPA row for {@code event_history} — mirrors {@link com.drones.vision.platform.Event} field-for-
 * field; {@code EventHistoryMapper} owns the mapping in both directions.
 *
 * <p>{@code id} is the domain's own event id (a {@code String}, not a wrapped {@code UUID} — {@link
 * com.drones.vision.platform.Event#id()} is already a plain string and every production caller
 * fills it from {@code UUID.randomUUID().toString()}, but the type itself makes no such promise, so
 * this column is {@code VARCHAR} rather than {@code UUID} to avoid rejecting a value a future caller
 * legitimately supplies).
 *
 * <p>{@code streamId} is nullable — {@link com.drones.vision.platform.Event#streamId()} is null for
 * every asset-scoped event type (device/battery/link/geofence/divergence), the majority of what
 * this table actually stores once {@code EventType#DETECTION} is excluded at the publisher (see
 * {@code PersistingEventPublisher}).
 *
 * <p>{@code attributes} stores the whole free-form {@code Map<String,String>} as one jsonb column
 * via Hibernate's native JSON support — same mechanism/rationale as {@code
 * AuditEntryEntity#details}: read back whole with the row, never queried into by individual key.
 *
 * <p>{@code type} reuses the domain {@link EventType} enum directly in an {@code
 * @Enumerated(EnumType.STRING)} field, the same convention {@code AuditEntryEntity#action} follows.
 *
 * <p>No FK to any other table (not {@code stream_id} to anything) — same "no cross-entity foreign
 * keys" convention as the rest of this schema; a stream can be long gone by the time its history is
 * read back, and that must not make the row unresolvable.
 */
@Entity
@Table(name = "event_history")
public class EventHistoryEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "stream_id")
    private UUID streamId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private EventType type;

    @Column(name = "message", nullable = false, columnDefinition = "text")
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes", columnDefinition = "jsonb", nullable = false)
    private Map<String, String> attributes = Map.of();

    /** JPA only. */
    protected EventHistoryEntity() {
    }

    public EventHistoryEntity(String id, UUID streamId, Instant occurredAt, EventType type, String message,
                               Map<String, String> attributes) {
        this.id = id;
        this.streamId = streamId;
        this.occurredAt = occurredAt;
        this.type = type;
        this.message = message;
        this.attributes = Map.copyOf(attributes);
    }

    public String id() {
        return id;
    }

    public UUID streamId() {
        return streamId;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public EventType type() {
        return type;
    }

    public String message() {
        return message;
    }

    public Map<String, String> attributes() {
        return attributes;
    }
}

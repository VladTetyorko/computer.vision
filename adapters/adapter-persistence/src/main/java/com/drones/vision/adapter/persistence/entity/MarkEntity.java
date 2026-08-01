package com.drones.vision.adapter.persistence.entity;

import com.drones.vision.domain.model.MarkKind;
import com.drones.vision.domain.model.MarkSource;
import com.drones.vision.domain.model.MarkStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA row for {@code marks} — mirrors {@link com.drones.vision.domain.model.Mark} field-for-field
 * (docs/TACTICAL-MARKS-PLAN.md §3); {@link com.drones.vision.adapter.persistence.JpaMarkRepository}
 * owns the mapping in both directions.
 *
 * <p>Unlike {@link GeofenceZoneEntity#polygon} (a jsonb {@code List<GeoPosition>}), a mark's
 * {@code position} is a single point, so it is flattened to plain {@code latitude}/{@code
 * longitude}/{@code altitude_meters} columns — same "flatten a small value type into columns"
 * choice {@link AssetUsageEntity} makes for {@code GeoPosition} (rather than {@link
 * GeofenceZoneEntity}'s jsonb-column choice for a whole polygon). {@code ownership} is flattened
 * to {@code owner_id}/{@code group_id}, the same choice {@link AssetEntity} makes for {@link
 * com.drones.vision.domain.model.Ownership}.
 *
 * <p>{@code kind}/{@code status}/{@code source} reuse the domain {@link MarkKind}/{@link
 * MarkStatus}/{@link MarkSource} enums directly in {@code @Enumerated(EnumType.STRING)} fields —
 * the same "domain enums reused directly in entity fields" convention {@link GeofenceZoneEntity#kind}
 * already follows.
 *
 * <p>No FK to any other table — same "no cross-entity foreign keys" convention as the rest of this
 * schema (see MODULE.md's Conventions); {@code owner_id}/{@code group_id} are plain UUID columns
 * with no referential check, matching the in-memory reference repository's own lack of one.
 */
@Entity
@Table(name = "marks")
public class MarkEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private MarkKind kind;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "note")
    private String note;

    @Column(name = "latitude", nullable = false)
    private double latitude;

    @Column(name = "longitude", nullable = false)
    private double longitude;

    @Column(name = "altitude_meters")
    private Double altitudeMeters;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private MarkStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private MarkSource source;

    /** JPA only. */
    protected MarkEntity() {
    }

    public MarkEntity(UUID id, MarkKind kind, String label, String note, double latitude, double longitude,
                       Double altitudeMeters, UUID ownerId, UUID groupId, Instant createdAt, MarkStatus status,
                       MarkSource source) {
        this.id = id;
        this.kind = kind;
        this.label = label;
        this.note = note;
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitudeMeters = altitudeMeters;
        this.ownerId = ownerId;
        this.groupId = groupId;
        this.createdAt = createdAt;
        this.status = status;
        this.source = source;
    }

    public UUID id() {
        return id;
    }

    public MarkKind kind() {
        return kind;
    }

    public String label() {
        return label;
    }

    public String note() {
        return note;
    }

    public double latitude() {
        return latitude;
    }

    public double longitude() {
        return longitude;
    }

    public Double altitudeMeters() {
        return altitudeMeters;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public UUID groupId() {
        return groupId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public MarkStatus status() {
        return status;
    }

    public MarkSource source() {
        return source;
    }
}

package com.drones.vision.adapter.persistence.entity;

import com.drones.vision.domain.model.DrawKind;
import com.drones.vision.domain.model.GeoPosition;

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
 * A line/polygon/arrow/text annotation on a map layer (docs/plans/done/MAP-REWORK-PLAN.md §2.1/§4.4, {@code
 * V12__map_layers.sql}) — mirrors the domain {@code Drawing} field for field.
 *
 * <p>{@code points} stores the whole ordered {@code List<GeoPosition>} as one jsonb column, the same
 * mechanism and rationale as {@code GeofenceZoneEntity#polygon} (a plain immutable record list
 * Jackson serializes natively, only ever read back whole) — and the deliberate opposite of {@code
 * MarkEntity}, whose single {@link GeoPosition} is flattened into columns instead.
 *
 * <p>{@code layer_id} carries <strong>no foreign key</strong> to {@code map_layers}, matching this
 * module's standing "no cross-entity foreign keys" convention: the in-memory reference repository
 * ({@code InMemoryDrawingRepository}, vision-app devsupport) does no referential check, and the
 * layer → drawings cascade is performed in application code by {@code DefaultMapLayerService#delete}
 * (which must emit a {@code MapEvent} per cascaded row, something {@code ON DELETE CASCADE} could
 * not do). It is indexed, since listing a layer's drawings is the access path.
 */
@Entity
@Table(name = "map_drawings")
public class MapDrawingEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "layer_id", nullable = false)
    private UUID layerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private DrawKind kind;

    @Column(name = "label", length = 120)
    private String label;

    @Column(name = "color_token", length = 30)
    private String colorToken;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "points", columnDefinition = "jsonb", nullable = false)
    private List<GeoPosition> points = new ArrayList<>();

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MapDrawingEntity() {
    }

    public MapDrawingEntity(UUID id, UUID layerId, DrawKind kind, String label, String colorToken,
                             List<GeoPosition> points, UUID ownerUserId, UUID groupId, Instant createdAt) {
        this.id = id;
        this.layerId = layerId;
        this.kind = kind;
        this.label = label;
        this.colorToken = colorToken;
        this.points = new ArrayList<>(points);
        this.ownerUserId = ownerUserId;
        this.groupId = groupId;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public UUID layerId() {
        return layerId;
    }

    public DrawKind kind() {
        return kind;
    }

    public String label() {
        return label;
    }

    public String colorToken() {
        return colorToken;
    }

    public List<GeoPosition> points() {
        return points;
    }

    public UUID ownerUserId() {
        return ownerUserId;
    }

    public UUID groupId() {
        return groupId;
    }

    public Instant createdAt() {
        return createdAt;
    }
}

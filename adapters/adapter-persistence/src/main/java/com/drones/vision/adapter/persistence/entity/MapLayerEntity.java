package com.drones.vision.adapter.persistence.entity;

import com.drones.vision.domain.model.LayerKind;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A named, access-controlled map layer (docs/MAP-REWORK-PLAN.md §2.1/§4.4, {@code
 * V12__map_layers.sql}) — mirrors the domain {@code MapLayer} field for field.
 *
 * <p>{@code id} is the domain's own {@code LayerId}, not synthetic — a layer has real identity.
 * {@code kind} reuses the domain {@link LayerKind} enum directly ({@code @Enumerated(STRING)}), the
 * same convention {@code GeofenceZoneEntity#kind}/{@code MarkEntity#kind} follow. {@code ownership}
 * is flattened to {@code owner_user_id}/{@code group_id}, the same choice {@code AssetEntity}/{@code
 * MarkEntity} make.
 *
 * <p>{@code grants} is the one collection in this module stored as an <strong>element-collection
 * table</strong> rather than jsonb — see {@link LayerGrantEmbeddable}'s javadoc for why. It is
 * {@code EAGER}, matching {@code DeviceEntity#capabilities}/{@code AssetEntity#deviceIds}: the
 * repositories here open a short-lived {@code EntityManager} per call, so a lazy collection would be
 * unreadable by the time the caller sees the domain object.
 */
@Entity
@Table(name = "map_layers")
public class MapLayerEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private LayerKind kind;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "map_layer_grants", joinColumns = @JoinColumn(name = "layer_id"))
    private List<LayerGrantEmbeddable> grants = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MapLayerEntity() {
    }

    public MapLayerEntity(UUID id, String name, LayerKind kind, UUID ownerUserId, UUID groupId,
                           List<LayerGrantEmbeddable> grants, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.kind = kind;
        this.ownerUserId = ownerUserId;
        this.groupId = groupId;
        this.grants = new ArrayList<>(grants);
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public LayerKind kind() {
        return kind;
    }

    public UUID ownerUserId() {
        return ownerUserId;
    }

    public UUID groupId() {
        return groupId;
    }

    public List<LayerGrantEmbeddable> grants() {
        return grants;
    }

    public Instant createdAt() {
        return createdAt;
    }
}

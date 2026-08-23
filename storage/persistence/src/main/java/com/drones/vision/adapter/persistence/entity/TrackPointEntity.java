package com.drones.vision.adapter.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA row for {@code projected_track_points} — mirrors {@link com.drones.vision.map.domain.model.TrackPoint}
 * field-for-field (docs/plans/done/FIXED-CAMERA-GEO-PLAN.md decision D3/§7, {@code
 * V22__fixed_camera_geo.sql}); {@code TrackPointMapper} owns the mapping in both directions.
 *
 * <p>{@code id} is a synthetic {@code BIGINT GENERATED ALWAYS AS IDENTITY} column the schema
 * itself generates — not invented in Java, the same mechanism {@link DbAuditLogEntity#id()} uses —
 * since {@link com.drones.vision.map.domain.model.TrackPoint} carries no id of its own (append-only,
 * decimated machine output; a stored point is never looked up by id, only by {@code (assetId,
 * trackId)}, or pruned by {@code capturedAt}), so there is nothing to read back after {@code
 * persist} and nothing for {@code TrackPointMapper#toEntity} to pass in. {@code position} is
 * flattened to {@code latitude}/{@code longitude}/nullable {@code altitude_meters} columns, the
 * same choice {@link CameraPoseEntity} makes for its own position.
 *
 * <p>No FK to any other table — same "no cross-entity foreign keys" convention as the rest of this
 * schema. <strong>Excluded</strong> from {@code db_audit_log} — see {@code
 * V22__fixed_camera_geo.sql}'s own header for why, the opposite of {@link CameraPoseEntity}.
 */
@Entity
@Table(name = "projected_track_points")
public class TrackPointEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "track_id", nullable = false)
    private long trackId;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "layer_id", nullable = false)
    private UUID layerId;

    @Column(name = "latitude", nullable = false)
    private double latitude;

    @Column(name = "longitude", nullable = false)
    private double longitude;

    @Column(name = "altitude_meters")
    private Double altitudeMeters;

    @Column(name = "error_radius_meters", nullable = false)
    private double errorRadiusMeters;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    /** JPA only. */
    protected TrackPointEntity() {
    }

    /** A new, not-yet-persisted row — {@code id} is assigned by the database on insert. */
    public TrackPointEntity(UUID assetId, long trackId, String label, UUID layerId, double latitude,
                             double longitude, Double altitudeMeters, double errorRadiusMeters,
                             Instant capturedAt) {
        this.assetId = assetId;
        this.trackId = trackId;
        this.label = label;
        this.layerId = layerId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitudeMeters = altitudeMeters;
        this.errorRadiusMeters = errorRadiusMeters;
        this.capturedAt = capturedAt;
    }

    public Long id() {
        return id;
    }

    public UUID assetId() {
        return assetId;
    }

    public long trackId() {
        return trackId;
    }

    public String label() {
        return label;
    }

    public UUID layerId() {
        return layerId;
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

    public double errorRadiusMeters() {
        return errorRadiusMeters;
    }

    public Instant capturedAt() {
        return capturedAt;
    }
}

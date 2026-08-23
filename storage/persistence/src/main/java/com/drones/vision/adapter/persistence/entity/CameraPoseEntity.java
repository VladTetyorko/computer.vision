package com.drones.vision.adapter.persistence.entity;

import com.drones.vision.map.domain.model.CameraPoseSource;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA row for {@code camera_poses} — mirrors {@link com.drones.vision.map.domain.model.CameraPose}
 * field-for-field (docs/plans/done/FIXED-CAMERA-GEO-PLAN.md decision D4, {@code
 * V22__fixed_camera_geo.sql}); {@code CameraPoseMapper} owns the mapping in both directions.
 *
 * <p>{@code asset_id} is the primary key, not a synthetic one — one row per asset, {@code save} is
 * always an upsert, the same "owning id doubles as the uniqueness constraint" choice {@link
 * AssetImageEntity} makes. {@code position} (a single {@code GeoPosition}) is flattened to
 * {@code latitude}/{@code longitude}/nullable {@code altitude_meters} columns, the same choice
 * {@link MarkEntity} makes for its own single-point position — even though altitude is currently
 * unused by the projection math, dropping the column would silently discard it on every round
 * trip. {@code source} reuses the domain {@link CameraPoseSource} enum directly ({@code
 * @Enumerated(EnumType.STRING)}), the same convention {@link MarkEntity#source()} follows for its
 * own provenance enum.
 *
 * <p>No FK to any other table — same "no cross-entity foreign keys" convention as the rest of this
 * schema. This table <strong>is</strong> audited ({@code trg_audit_camera_poses}) — see {@code
 * V22__fixed_camera_geo.sql}'s own header for why, the opposite of {@link TrackPointEntity}.
 */
@Entity
@Table(name = "camera_poses")
public class CameraPoseEntity {

    @Id
    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "latitude", nullable = false)
    private double latitude;

    @Column(name = "longitude", nullable = false)
    private double longitude;

    @Column(name = "altitude_meters")
    private Double altitudeMeters;

    @Column(name = "agl_meters", nullable = false)
    private double aglMeters;

    @Column(name = "yaw_degrees", nullable = false)
    private double yawDegrees;

    @Column(name = "pitch_degrees", nullable = false)
    private double pitchDegrees;

    @Column(name = "hfov_degrees", nullable = false)
    private double hfovDegrees;

    @Column(name = "target_layer_id")
    private UUID targetLayerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 12)
    private CameraPoseSource source;

    @Column(name = "rms_error_pixels")
    private Double rmsErrorPixels;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false)
    private UUID updatedBy;

    /** JPA only. */
    protected CameraPoseEntity() {
    }

    public CameraPoseEntity(UUID assetId, double latitude, double longitude, Double altitudeMeters,
                             double aglMeters, double yawDegrees, double pitchDegrees, double hfovDegrees,
                             UUID targetLayerId, CameraPoseSource source, Double rmsErrorPixels,
                             Instant updatedAt, UUID updatedBy) {
        this.assetId = assetId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitudeMeters = altitudeMeters;
        this.aglMeters = aglMeters;
        this.yawDegrees = yawDegrees;
        this.pitchDegrees = pitchDegrees;
        this.hfovDegrees = hfovDegrees;
        this.targetLayerId = targetLayerId;
        this.source = source;
        this.rmsErrorPixels = rmsErrorPixels;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    public UUID assetId() {
        return assetId;
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

    public double aglMeters() {
        return aglMeters;
    }

    public double yawDegrees() {
        return yawDegrees;
    }

    public double pitchDegrees() {
        return pitchDegrees;
    }

    public double hfovDegrees() {
        return hfovDegrees;
    }

    public UUID targetLayerId() {
        return targetLayerId;
    }

    public CameraPoseSource source() {
        return source;
    }

    public Double rmsErrorPixels() {
        return rmsErrorPixels;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public UUID updatedBy() {
        return updatedBy;
    }
}

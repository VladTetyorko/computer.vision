package com.drones.vision.adapter.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA row for {@code asset_usages} — mirrors {@link com.drones.vision.domain.model.AssetUsage}
 * field-for-field, with {@code startPosition}/{@code lastPosition} each flattened to a
 * latitude/longitude/altitude column triple (same "flatten a small value type into columns"
 * choice {@code AssetEntity} makes for {@code Ownership}, rather than a jsonb column). A
 * latitude/longitude pair is {@code null} together iff the corresponding {@code GeoPosition} is
 * {@code null} — {@link com.drones.vision.domain.model.GeoPosition#latitude()}/{@code longitude()}
 * are non-nullable primitives on the domain side, so only the altitude column is independently
 * nullable once a position exists.
 *
 * <p>{@code streamId} (docs/plans/done/MVP2-PLAN.md §R, R-a2, {@code V4__usage_stream_id.sql}) is a nullable
 * UUID column — additive on top of V1-V3, so every pre-existing row simply reads back {@code
 * null} (a legacy usage, honestly carrying no stream link — see {@link
 * com.drones.vision.domain.model.AssetUsage}'s javadoc).
 *
 * <p>{@link com.drones.vision.adapter.persistence.JpaAssetUsageRepository} owns the mapping in
 * both directions. No FK to {@code assets} — same no-cross-entity-FK convention as the P-a schema
 * (see {@code CategoryEntity}/{@code AssetEntity}'s javadoc); {@code asset_id} is indexed instead
 * (see {@code V3__history.sql}) for {@code findRecentByAsset}/{@code findOpenByAsset}.
 */
@Entity
@Table(name = "asset_usages")
public class AssetUsageEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "start_latitude")
    private Double startLatitude;

    @Column(name = "start_longitude")
    private Double startLongitude;

    @Column(name = "start_altitude_meters")
    private Double startAltitudeMeters;

    @Column(name = "last_latitude")
    private Double lastLatitude;

    @Column(name = "last_longitude")
    private Double lastLongitude;

    @Column(name = "last_altitude_meters")
    private Double lastAltitudeMeters;

    @Column(name = "sample_count", nullable = false)
    private long sampleCount;

    @Column(name = "stream_id")
    private UUID streamId;

    /** JPA only. */
    protected AssetUsageEntity() {
    }

    public AssetUsageEntity(UUID id, UUID assetId, Instant startedAt, Instant endedAt, Double startLatitude,
                             Double startLongitude, Double startAltitudeMeters, Double lastLatitude,
                             Double lastLongitude, Double lastAltitudeMeters, long sampleCount, UUID streamId) {
        this.id = id;
        this.assetId = assetId;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.startLatitude = startLatitude;
        this.startLongitude = startLongitude;
        this.startAltitudeMeters = startAltitudeMeters;
        this.lastLatitude = lastLatitude;
        this.lastLongitude = lastLongitude;
        this.lastAltitudeMeters = lastAltitudeMeters;
        this.sampleCount = sampleCount;
        this.streamId = streamId;
    }

    public UUID id() {
        return id;
    }

    public UUID assetId() {
        return assetId;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant endedAt() {
        return endedAt;
    }

    public Double startLatitude() {
        return startLatitude;
    }

    public Double startLongitude() {
        return startLongitude;
    }

    public Double startAltitudeMeters() {
        return startAltitudeMeters;
    }

    public Double lastLatitude() {
        return lastLatitude;
    }

    public Double lastLongitude() {
        return lastLongitude;
    }

    public Double lastAltitudeMeters() {
        return lastAltitudeMeters;
    }

    public long sampleCount() {
        return sampleCount;
    }

    public UUID streamId() {
        return streamId;
    }
}

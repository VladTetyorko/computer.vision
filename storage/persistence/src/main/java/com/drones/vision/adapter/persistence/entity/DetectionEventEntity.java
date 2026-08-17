package com.drones.vision.adapter.persistence.entity;

import com.drones.vision.perception.domain.model.DetectionEventState;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA row for {@code detection_events} — mirrors {@link com.drones.vision.perception.domain.model.DetectionEvent}
 * field-for-field (docs/plans/active/POSTGRES-ONLY-CONTEXT.md W3); {@code DetectionEventMapper} owns the mapping
 * in both directions.
 *
 * <p>{@code id} is the domain's own {@code DetectionEventId} (not synthetic — unlike {@link
 * DetectionResultEntity}, an event has real identity and mutates over its own open lifetime, so it
 * needs a stable key to upsert by; {@link
 * com.drones.vision.adapter.persistence.repository.JpaDetectionEventRepository#save} is a genuine
 * merge, not a {@code persist}). {@code asset_id} is nullable — the device streaming may not
 * (yet) belong to any asset, mirroring the domain record's own nullability.
 *
 * <p>{@code position} is a single, entirely-optional {@code GeoPosition} (the domain record's
 * javadoc: {@code null} when unresolvable at open time), so it is flattened to nullable
 * {@code position_latitude}/{@code position_longitude}/{@code position_altitude_meters} columns
 * exactly like {@link AssetUsageEntity}'s {@code startPosition}/{@code lastPosition} — a
 * latitude/longitude pair is {@code null} together iff the position itself is {@code null}, with
 * altitude independently nullable once a position exists. This is the "flatten a small,
 * genuinely-optional value type into columns" choice, the opposite of {@link
 * GeofenceZoneEntity#polygon}'s jsonb choice for a whole vertex list.
 *
 * <p>{@code state} reuses the domain {@link DetectionEventState} enum directly in an {@code
 * @Enumerated(EnumType.STRING)} field — same convention as {@code GeofenceZoneEntity#kind}.
 *
 * <p>No FK to any other table — same "no cross-entity foreign keys" convention as the rest of this
 * schema. {@code (stream_id, last_seen)} is indexed for {@code findByStream}'s per-stream
 * newest-first query and the retention prune query; {@code last_seen} alone is indexed for {@code
 * findRecent}'s cross-stream newest-first query (see {@code V15__detection_events.sql}).
 */
@Entity
@Table(name = "detection_events")
public class DetectionEventEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "stream_id", nullable = false)
    private UUID streamId;

    @Column(name = "asset_id")
    private UUID assetId;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "peak_confidence", nullable = false)
    private double peakConfidence;

    @Column(name = "first_seen", nullable = false)
    private Instant firstSeen;

    @Column(name = "last_seen", nullable = false)
    private Instant lastSeen;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private DetectionEventState state;

    @Column(name = "position_latitude")
    private Double positionLatitude;

    @Column(name = "position_longitude")
    private Double positionLongitude;

    @Column(name = "position_altitude_meters")
    private Double positionAltitudeMeters;

    /** JPA only. */
    protected DetectionEventEntity() {
    }

    public DetectionEventEntity(UUID id, UUID streamId, UUID assetId, String label, double peakConfidence,
                                 Instant firstSeen, Instant lastSeen, DetectionEventState state,
                                 Double positionLatitude, Double positionLongitude, Double positionAltitudeMeters) {
        this.id = id;
        this.streamId = streamId;
        this.assetId = assetId;
        this.label = label;
        this.peakConfidence = peakConfidence;
        this.firstSeen = firstSeen;
        this.lastSeen = lastSeen;
        this.state = state;
        this.positionLatitude = positionLatitude;
        this.positionLongitude = positionLongitude;
        this.positionAltitudeMeters = positionAltitudeMeters;
    }

    public UUID id() {
        return id;
    }

    public UUID streamId() {
        return streamId;
    }

    public UUID assetId() {
        return assetId;
    }

    public String label() {
        return label;
    }

    public double peakConfidence() {
        return peakConfidence;
    }

    public Instant firstSeen() {
        return firstSeen;
    }

    public Instant lastSeen() {
        return lastSeen;
    }

    public DetectionEventState state() {
        return state;
    }

    public Double positionLatitude() {
        return positionLatitude;
    }

    public Double positionLongitude() {
        return positionLongitude;
    }

    public Double positionAltitudeMeters() {
        return positionAltitudeMeters;
    }
}

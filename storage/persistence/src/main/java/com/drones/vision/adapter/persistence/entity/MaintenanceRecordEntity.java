package com.drones.vision.adapter.persistence.entity;

import com.drones.vision.warehouse.domain.model.MaintenanceKind;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA row for {@code maintenance_records} — mirrors {@link
 * com.drones.vision.warehouse.domain.model.MaintenanceRecord} field-for-field
 * (docs/plans/active/WAREHOUSE-UX-PLAN.md D7).
 *
 * <p>{@code id} is the domain's own {@code MaintenanceId}, not synthetic — an operator refers to
 * a specific record when closing it (same choice {@link ControlProfileEntity}/{@link
 * GeofenceZoneEntity} make). {@code kind} reuses the domain {@link MaintenanceKind} enum directly
 * in an {@code @Enumerated(EnumType.STRING)} field, the same convention {@code
 * AssetUsageEntity#phase} follows. No FK to {@code assets} — this schema's standing convention.
 */
@Entity
@Table(name = "maintenance_records")
public class MaintenanceRecordEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    private MaintenanceKind kind;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "opened_by", nullable = false)
    private UUID openedBy;

    @Column(name = "summary", length = 2000)
    private String summary;

    @Column(name = "flight_seconds_at")
    private Long flightSecondsAt;

    /** JPA only. */
    protected MaintenanceRecordEntity() {
    }

    public MaintenanceRecordEntity(UUID id, UUID assetId, MaintenanceKind kind, Instant openedAt,
                                    Instant closedAt, UUID openedBy, String summary, Long flightSecondsAt) {
        this.id = id;
        this.assetId = assetId;
        this.kind = kind;
        this.openedAt = openedAt;
        this.closedAt = closedAt;
        this.openedBy = openedBy;
        this.summary = summary;
        this.flightSecondsAt = flightSecondsAt;
    }

    public UUID id() {
        return id;
    }

    public UUID assetId() {
        return assetId;
    }

    public MaintenanceKind kind() {
        return kind;
    }

    public Instant openedAt() {
        return openedAt;
    }

    public Instant closedAt() {
        return closedAt;
    }

    public UUID openedBy() {
        return openedBy;
    }

    public String summary() {
        return summary;
    }

    public Long flightSecondsAt() {
        return flightSecondsAt;
    }
}

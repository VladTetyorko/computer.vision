package com.drones.vision.adapter.persistence.entity;

import com.drones.vision.domain.model.GeoPosition;
import com.drones.vision.domain.model.ZoneKind;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JPA row for {@code geofence_zones} — mirrors {@link com.drones.vision.domain.model.GeofenceZone}
 * field-for-field (docs/plans/done/OPS-CORE-PLAN.md §G); {@link
 * com.drones.vision.adapter.persistence.JpaGeofenceRepository} owns the mapping in both
 * directions.
 *
 * <p>{@code polygon} stores the whole {@code List<GeoPosition>} as one jsonb column via
 * Hibernate's native JSON support — same mechanism/rationale as {@code
 * DetectionResultEntity#detections}: {@link GeoPosition} is a plain immutable record Jackson
 * serializes natively, and a zone's polygon is only ever read back whole, never queried into by
 * individual vertex, so a normalized child table would add schema without adding any real query
 * capability.
 *
 * <p>{@code kind} reuses the domain {@link ZoneKind} enum directly in an {@code
 * @Enumerated(EnumType.STRING)} field — the same "domain enums reused directly in entity fields"
 * convention {@code Capability}/{@code LifecycleState} already follow elsewhere in this module
 * (see MODULE.md's Conventions).
 *
 * <p>No FK to any other table — zones are global reference data with no relationship to
 * assets/devices, same "no cross-entity foreign keys" convention as the rest of this schema.
 */
@Entity
@Table(name = "geofence_zones")
public class GeofenceZoneEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private ZoneKind kind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "polygon", columnDefinition = "jsonb", nullable = false)
    private List<GeoPosition> polygon = new ArrayList<>();

    @Column(name = "max_altitude_meters")
    private Double maxAltitudeMeters;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /** JPA only. */
    protected GeofenceZoneEntity() {
    }

    public GeofenceZoneEntity(UUID id, String name, ZoneKind kind, List<GeoPosition> polygon,
                              Double maxAltitudeMeters, boolean enabled) {
        this.id = id;
        this.name = name;
        this.kind = kind;
        this.polygon = new ArrayList<>(polygon);
        this.maxAltitudeMeters = maxAltitudeMeters;
        this.enabled = enabled;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public ZoneKind kind() {
        return kind;
    }

    public List<GeoPosition> polygon() {
        return polygon;
    }

    public Double maxAltitudeMeters() {
        return maxAltitudeMeters;
    }

    public boolean enabled() {
        return enabled;
    }
}

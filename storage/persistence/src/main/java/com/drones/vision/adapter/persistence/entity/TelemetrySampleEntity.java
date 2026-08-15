package com.drones.vision.adapter.persistence.entity;

import com.drones.vision.kernel.FlightState;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * JPA row for {@code telemetry_samples} — mirrors {@link com.drones.vision.kernel.Telemetry}
 * field-for-field. {@link com.drones.vision.kernel.Telemetry} itself carries no identity of
 * its own (it is an append-only sample, not an aggregate), so {@code id} is a synthetic UUID this
 * entity invents at save time ({@link com.drones.vision.adapter.persistence.JpaTelemetryRepository}
 * generates it) and never surfaces back to the domain — the round-tripped {@code Telemetry} is
 * identified only by {@code (usageId, deviceId, at)}, exactly as the in-memory reference
 * implementation identifies samples (by list position within a usage, i.e. not at all).
 *
 * <p>{@code flightState} (docs/plans/done/FC-INTEGRATIONS-PLAN.md F-b) stores the whole {@link FlightState}
 * as one nullable jsonb column via the same Hibernate native JSON support {@code
 * DetectionResultEntity#detections} already uses for a plain immutable record tree — no
 * persistence-local wrapper type is needed, {@link FlightState} (plus its {@code List<String>
 * armingBlockers}) serializes/deserializes as-is. {@code null} means either the sample pre-dates
 * this column (older row) or the device reported no flight-controller state at all — both read
 * back as {@link com.drones.vision.kernel.Telemetry#flightState()} {@code == null}, exactly
 * matching {@code Telemetry}'s own nullable-9th-component contract; there is no way to distinguish
 * the two cases from this column alone, and nothing needs to.
 *
 * <p>No FK to {@code asset_usages}/{@code devices} — same no-cross-entity-FK convention as the
 * P-a schema. {@code (usage_id, at)} is indexed (see {@code V3__history.sql}) for {@code
 * findByUsage} and the retention prune query
 * ({@link com.drones.vision.adapter.persistence.JpaTelemetryRepository}'s Gotchas).
 */
@Entity
@Table(name = "telemetry_samples")
public class TelemetrySampleEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "usage_id", nullable = false)
    private UUID usageId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "at", nullable = false)
    private Instant at;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "altitude_meters")
    private Double altitudeMeters;

    @Column(name = "heading_degrees")
    private Double headingDegrees;

    @Column(name = "battery_percent")
    private Double batteryPercent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extra", columnDefinition = "jsonb", nullable = false)
    private Map<String, Double> extra = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "flight_state", columnDefinition = "jsonb")
    private FlightState flightState;

    /** JPA only. */
    protected TelemetrySampleEntity() {
    }

    public TelemetrySampleEntity(UUID id, UUID usageId, UUID deviceId, Instant at, Double latitude, Double longitude,
                                  Double altitudeMeters, Double headingDegrees, Double batteryPercent,
                                  Map<String, Double> extra, FlightState flightState) {
        this.id = id;
        this.usageId = usageId;
        this.deviceId = deviceId;
        this.at = at;
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitudeMeters = altitudeMeters;
        this.headingDegrees = headingDegrees;
        this.batteryPercent = batteryPercent;
        this.extra = new LinkedHashMap<>(extra);
        this.flightState = flightState;
    }

    public UUID id() {
        return id;
    }

    public UUID usageId() {
        return usageId;
    }

    public UUID deviceId() {
        return deviceId;
    }

    public Instant at() {
        return at;
    }

    public Double latitude() {
        return latitude;
    }

    public Double longitude() {
        return longitude;
    }

    public Double altitudeMeters() {
        return altitudeMeters;
    }

    public Double headingDegrees() {
        return headingDegrees;
    }

    public Double batteryPercent() {
        return batteryPercent;
    }

    public Map<String, Double> extra() {
        return extra;
    }

    public FlightState flightState() {
        return flightState;
    }
}

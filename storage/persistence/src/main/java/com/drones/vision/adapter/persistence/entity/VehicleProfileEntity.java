package com.drones.vision.adapter.persistence.entity;

import com.drones.vision.flight.domain.model.FlightPhase;
import com.drones.vision.flight.domain.model.MessageObservation;
import com.drones.vision.flight.domain.model.ParameterReading;

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
 * JPA row for {@code vehicle_profiles} — mirrors {@link com.drones.vision.flight.domain.model.VehicleProfile}
 * field-for-field (docs/plans/active/DRONE-ONBOARDING-PLAN.md O5); {@link
 * com.drones.vision.adapter.persistence.mapper.VehicleProfileMapper} owns the mapping in both
 * directions.
 *
 * <p>{@code id} is a synthetic {@code UUID} this entity invents at save time — {@code
 * VehicleProfile} itself carries no identity (an append-only observation keyed by {@code
 * linkKey}/{@code DeviceId} at the port level, not a row id), same rationale as {@code
 * DetectionResultEntity}/{@code TelemetrySampleEntity}. {@code deviceId} is the FK the port's own
 * {@code save(DeviceId, VehicleProfile)}/{@code findLatest(DeviceId)} key on — same "key lives in
 * the port call, not a domain field" idiom as {@code TelemetrySampleEntity#usageId}.
 *
 * <p>{@code capabilityFlags}/{@code messages}/{@code parameters} are jsonb — each row is only ever
 * read back whole ({@code findLatest} returns one {@code VehicleProfile}), never queried into by
 * individual message/parameter, same "jsonb over a normalized child table" convention as {@code
 * DetectionResultEntity#detections}.
 *
 * <p>{@code usageId}/{@code phase} (docs/plans/active/DRONE-ONBOARDING-PLAN.md O11, {@code
 * V20__vehicle_profile_usage_link.sql}) are both nullable — the flight passport's attachment point:
 * a row saved through {@code save(DeviceId, VehicleProfile)} (readiness's own ad hoc probes, the
 * pre-registration candidate probe) carries neither, while one saved through {@code save(DeviceId,
 * UsageId, FlightPhase, VehicleProfile)} carries both together (there is no state where exactly one
 * is set). {@code phase} reuses the domain {@link FlightPhase} enum directly ({@code
 * @Enumerated(EnumType.STRING)}), same convention {@code AssetUsageEntity#phase} follows for {@code
 * UsagePhase} — restricting it to {@code PREFLIGHT}/{@code POSTFLIGHT} is the application layer's
 * job ({@code DefaultVehicleProfileService#captureSnapshot}), not this entity's or the schema's.
 */
@Entity
@Table(name = "vehicle_profiles")
public class VehicleProfileEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "link_key", nullable = false)
    private String linkKey;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @Column(name = "sysid")
    private Integer sysid;

    @Column(name = "firmware", length = 32)
    private String firmware;

    @Column(name = "firmware_version")
    private String firmwareVersion;

    @Column(name = "vehicle_kind")
    private String vehicleKind;

    @Column(name = "capability_bitmask")
    private Long capabilityBitmask;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "capability_flags", columnDefinition = "jsonb", nullable = false)
    private List<String> capabilityFlags = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "messages", columnDefinition = "jsonb", nullable = false)
    private List<MessageObservation> messages = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameters", columnDefinition = "jsonb", nullable = false)
    private List<ParameterReading> parameters = new ArrayList<>();

    @Column(name = "link_bytes_per_second")
    private Long linkBytesPerSecond;

    @Column(name = "complete", nullable = false)
    private boolean complete;

    @Column(name = "incomplete_reason")
    private String incompleteReason;

    @Column(name = "usage_id")
    private UUID usageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "phase", length = 20)
    private FlightPhase phase;

    /** JPA only. */
    protected VehicleProfileEntity() {
    }

    public VehicleProfileEntity(UUID id, UUID deviceId, String linkKey, Instant observedAt, Integer sysid,
                                 String firmware, String firmwareVersion, String vehicleKind,
                                 Long capabilityBitmask, List<String> capabilityFlags,
                                 List<MessageObservation> messages, List<ParameterReading> parameters,
                                 Long linkBytesPerSecond, boolean complete, String incompleteReason,
                                 UUID usageId, FlightPhase phase) {
        this.id = id;
        this.deviceId = deviceId;
        this.linkKey = linkKey;
        this.observedAt = observedAt;
        this.sysid = sysid;
        this.firmware = firmware;
        this.firmwareVersion = firmwareVersion;
        this.vehicleKind = vehicleKind;
        this.capabilityBitmask = capabilityBitmask;
        this.capabilityFlags = new ArrayList<>(capabilityFlags);
        this.messages = new ArrayList<>(messages);
        this.parameters = new ArrayList<>(parameters);
        this.linkBytesPerSecond = linkBytesPerSecond;
        this.complete = complete;
        this.incompleteReason = incompleteReason;
        this.usageId = usageId;
        this.phase = phase;
    }

    public UUID id() {
        return id;
    }

    public UUID deviceId() {
        return deviceId;
    }

    public String linkKey() {
        return linkKey;
    }

    public Instant observedAt() {
        return observedAt;
    }

    public Integer sysid() {
        return sysid;
    }

    public String firmware() {
        return firmware;
    }

    public String firmwareVersion() {
        return firmwareVersion;
    }

    public String vehicleKind() {
        return vehicleKind;
    }

    public Long capabilityBitmask() {
        return capabilityBitmask;
    }

    public List<String> capabilityFlags() {
        return capabilityFlags;
    }

    public List<MessageObservation> messages() {
        return messages;
    }

    public List<ParameterReading> parameters() {
        return parameters;
    }

    public Long linkBytesPerSecond() {
        return linkBytesPerSecond;
    }

    public boolean complete() {
        return complete;
    }

    public String incompleteReason() {
        return incompleteReason;
    }

    public UUID usageId() {
        return usageId;
    }

    public FlightPhase phase() {
        return phase;
    }
}

package com.drones.vision.adapter.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigInteger;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * JPA row for {@code pairings} — mirrors {@link com.drones.vision.warehouse.domain.model.Pairing}
 * field-for-field (docs/plans/active/LINK-PAIRING-PLAN.md §3.3, {@code V37__pairing.sql}). {@link
 * com.drones.vision.adapter.persistence.mapper.PairingMapper} owns the mapping both ways.
 *
 * <p>Mutable, hand-registered in {@link com.drones.vision.adapter.persistence.config.PersistenceUnit}
 * — no package scanning, same convention every entity in this module follows.
 *
 * <p>{@code vehicleKey} is the raw 32 bytes {@code VehicleKey} wraps, stored as {@code bytea} with
 * no special {@code JdbcTypeCode} — the same plain {@code byte[]} mapping {@code AssetImageEntity}
 * already uses for image bytes.
 */
@Entity
@Table(name = "pairings")
public class PairingEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "sysid", nullable = false)
    private int sysid;

    @Column(name = "vehicle_key", nullable = false)
    private byte[] vehicleKey;

    @Column(name = "hardware_uid", precision = 20, scale = 0)
    private BigInteger hardwareUid;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "radio_bind_attributes", columnDefinition = "jsonb", nullable = false)
    private Map<String, String> radioBindAttributes = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "replaced_at")
    private Instant replacedAt;

    /** JPA only. */
    protected PairingEntity() {
    }

    public PairingEntity(UUID id, UUID deviceId, int sysid, byte[] vehicleKey, BigInteger hardwareUid,
                          Map<String, String> radioBindAttributes, Instant createdAt, Instant replacedAt) {
        this.id = id;
        this.deviceId = deviceId;
        this.sysid = sysid;
        this.vehicleKey = vehicleKey;
        this.hardwareUid = hardwareUid;
        this.radioBindAttributes = new LinkedHashMap<>(radioBindAttributes);
        this.createdAt = createdAt;
        this.replacedAt = replacedAt;
    }

    public UUID id() {
        return id;
    }

    public UUID deviceId() {
        return deviceId;
    }

    public int sysid() {
        return sysid;
    }

    public byte[] vehicleKey() {
        return vehicleKey;
    }

    public BigInteger hardwareUid() {
        return hardwareUid;
    }

    public Map<String, String> radioBindAttributes() {
        return radioBindAttributes;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant replacedAt() {
        return replacedAt;
    }
}

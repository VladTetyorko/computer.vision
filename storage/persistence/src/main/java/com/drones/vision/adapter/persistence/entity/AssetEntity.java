package com.drones.vision.adapter.persistence.entity;

import com.drones.vision.kernel.LifecycleState;
import com.drones.vision.warehouse.domain.model.InventoryState;

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

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * JPA row for {@code assets} (plus its {@code asset_devices} element-collection table) — mirrors
 * {@link com.drones.vision.warehouse.domain.model.Asset} field-for-field, with {@code category} flattened to
 * its slug and {@code ownership} flattened to its two ids; {@link
 * com.drones.vision.adapter.persistence.JpaAssetRepository} owns the mapping in both directions.
 *
 * <p>{@code categoryId} is a plain string, not a JPA {@code @ManyToOne} to {@link
 * CategoryEntity} — same rationale as {@link CategoryEntity#parentId}: no referential check in
 * the in-memory reference implementation this adapter must stay behavior-compatible with.
 */
@Entity
@Table(name = "assets")
public class AssetEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "category_id", nullable = false, length = 64)
    private String categoryId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "asset_devices", joinColumns = @JoinColumn(name = "asset_id"))
    @Column(name = "device_id", nullable = false)
    private Set<UUID> deviceIds = new LinkedHashSet<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes", columnDefinition = "jsonb", nullable = false)
    private Map<String, String> attributes = new LinkedHashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private LifecycleState state;

    @Column(name = "serial_number", length = 120)
    private String serialNumber;

    @Column(name = "make", length = 120)
    private String make;

    @Column(name = "model", length = 120)
    private String model;

    @Column(name = "registration", length = 120)
    private String registration;

    @Column(name = "custodian_id")
    private UUID custodianId;

    @Column(name = "location")
    private String location;

    @Column(name = "custody_since")
    private Instant custodySince;

    /** Stored values only — {@code IN_STOCK}/{@code MAINTENANCE}/{@code RETIRED}; see {@link
     * InventoryState#storable()}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "inventory_state", nullable = false, length = 32)
    private InventoryState inventoryState;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA only. */
    protected AssetEntity() {
    }

    public AssetEntity(UUID id, String displayName, String categoryId, UUID ownerId, UUID groupId,
                        Set<UUID> deviceIds, Map<String, String> attributes, LifecycleState state,
                        String serialNumber, String make, String model, String registration, UUID custodianId,
                        String location, Instant custodySince, InventoryState inventoryState, Instant createdAt,
                        Instant updatedAt) {
        this.id = id;
        this.displayName = displayName;
        this.categoryId = categoryId;
        this.ownerId = ownerId;
        this.groupId = groupId;
        this.deviceIds = new LinkedHashSet<>(deviceIds);
        this.attributes = new LinkedHashMap<>(attributes);
        this.state = state;
        this.serialNumber = serialNumber;
        this.make = make;
        this.model = model;
        this.registration = registration;
        this.custodianId = custodianId;
        this.location = location;
        this.custodySince = custodySince;
        this.inventoryState = inventoryState;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String categoryId() {
        return categoryId;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public UUID groupId() {
        return groupId;
    }

    public Set<UUID> deviceIds() {
        return deviceIds;
    }

    public Map<String, String> attributes() {
        return attributes;
    }

    public LifecycleState state() {
        return state;
    }

    public String serialNumber() {
        return serialNumber;
    }

    public String make() {
        return make;
    }

    public String model() {
        return model;
    }

    public String registration() {
        return registration;
    }

    public UUID custodianId() {
        return custodianId;
    }

    public String location() {
        return location;
    }

    public Instant custodySince() {
        return custodySince;
    }

    public InventoryState inventoryState() {
        return inventoryState;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}

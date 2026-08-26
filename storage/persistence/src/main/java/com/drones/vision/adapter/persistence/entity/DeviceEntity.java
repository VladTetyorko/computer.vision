package com.drones.vision.adapter.persistence.entity;

import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.DeviceOrigin;
import com.drones.vision.kernel.LifecycleState;

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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * JPA row for {@code devices} (plus its {@code device_capabilities} element-collection table) —
 * mirrors {@link com.drones.vision.warehouse.domain.model.Device} field-for-field, with {@code stream}
 * flattened into three columns; {@link com.drones.vision.adapter.persistence.JpaDeviceRepository}
 * owns the mapping in both directions.
 *
 * <p>Reuses the domain {@link Capability}/{@link LifecycleState} enums directly in
 * {@code @Enumerated} columns rather than declaring parallel adapter-local enums that would need
 * to be kept in sync by hand — the framework annotation lives on this entity's field, not on the
 * domain enum declaration itself, so {@code vision-domain} stays annotation-free.
 */
@Entity
@Table(name = "devices")
public class DeviceEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "device_capabilities", joinColumns = @JoinColumn(name = "device_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "capability", nullable = false)
    private Set<Capability> capabilities = new LinkedHashSet<>();

    @Column(name = "stream_protocol", nullable = false)
    private String streamProtocol;

    @Column(name = "stream_uri", nullable = false, length = 2048)
    private String streamUri;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "stream_options", columnDefinition = "jsonb", nullable = false)
    private Map<String, String> streamOptions = new LinkedHashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private LifecycleState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 32)
    private DeviceOrigin origin;

    /** JPA only. */
    protected DeviceEntity() {
    }

    public DeviceEntity(UUID id, String name, Set<Capability> capabilities, String streamProtocol,
                         String streamUri, Map<String, String> streamOptions, LifecycleState state,
                         DeviceOrigin origin) {
        this.id = id;
        this.name = name;
        this.capabilities = new LinkedHashSet<>(capabilities);
        this.streamProtocol = streamProtocol;
        this.streamUri = streamUri;
        this.streamOptions = new LinkedHashMap<>(streamOptions);
        this.state = state;
        this.origin = origin;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Set<Capability> capabilities() {
        return capabilities;
    }

    public String streamProtocol() {
        return streamProtocol;
    }

    public String streamUri() {
        return streamUri;
    }

    public Map<String, String> streamOptions() {
        return streamOptions;
    }

    public LifecycleState state() {
        return state;
    }

    public DeviceOrigin origin() {
        return origin;
    }
}

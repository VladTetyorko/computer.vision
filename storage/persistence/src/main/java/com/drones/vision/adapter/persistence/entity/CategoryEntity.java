package com.drones.vision.adapter.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * JPA row for {@code categories} — mirrors {@link com.drones.vision.warehouse.domain.model.DeviceCategory}
 * field-for-field; {@link com.drones.vision.adapter.persistence.JpaCategoryRepository} owns the
 * mapping in both directions, so this class never leaks outside this module.
 *
 * <p>{@code parentId} is a plain nullable string, not a JPA {@code @ManyToOne}: the domain
 * itself never requires a parent category to already exist (see {@code DeviceCategory}'s
 * javadoc), and the in-memory reference implementation this adapter must stay behavior-compatible
 * with performs no such check either — a real self-referencing foreign key would reject valid
 * in-memory-equivalent operations.
 */
@Entity
@Table(name = "categories")
public class CategoryEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "parent_id", length = 64)
    private String parentId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attribute_hints", columnDefinition = "jsonb", nullable = false)
    private List<String> attributeHints = new ArrayList<>();

    @Column(name = "connected", nullable = false)
    private boolean connected;

    /** JPA only. */
    protected CategoryEntity() {
    }

    public CategoryEntity(String id, String name, String parentId, List<String> attributeHints,
                           boolean connected) {
        this.id = id;
        this.name = name;
        this.parentId = parentId;
        this.attributeHints = new ArrayList<>(attributeHints);
        this.connected = connected;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String parentId() {
        return parentId;
    }

    public List<String> attributeHints() {
        return attributeHints;
    }

    public boolean connected() {
        return connected;
    }
}

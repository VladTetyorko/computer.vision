package com.drones.vision.adapter.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA row for {@code asset_images} — mirrors {@link com.drones.vision.domain.model.AssetImage}
 * plus the owning asset's id as its primary key (docs/UX-REWORK-PLAN.md §U-d item 3); {@link
 * com.drones.vision.adapter.persistence.JpaAssetImageRepository} owns the mapping in both
 * directions.
 *
 * <p>{@code assetId} is the primary key rather than a synthetic one — there is at most one image
 * per asset and a save is always an upsert, so the schema itself enforces "one row per asset"
 * without a separate unique constraint (see {@code V5__asset_images.sql}'s own comment). No JPA
 * relationship to {@code AssetEntity} — same "no referential integrity between repositories"
 * convention every other table in this schema already follows.
 */
@Entity
@Table(name = "asset_images")
public class AssetImageEntity {

    @Id
    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "data", nullable = false)
    private byte[] data;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA only. */
    protected AssetImageEntity() {
    }

    public AssetImageEntity(UUID assetId, String contentType, byte[] data, Instant updatedAt) {
        this.assetId = assetId;
        this.contentType = contentType;
        this.data = data.clone();
        this.updatedAt = updatedAt;
    }

    public UUID assetId() {
        return assetId;
    }

    public String contentType() {
        return contentType;
    }

    public byte[] data() {
        return data.clone();
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}

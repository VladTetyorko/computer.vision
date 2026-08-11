package com.drones.vision.adapter.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA row for {@code sample_images} — mirrors {@link com.drones.vision.domain.model.SampleImage}
 * plus the owning training sample's id as its primary key (docs/plans/done/CV-TRAINING-PLAN.md §1/§C); {@link
 * com.drones.vision.adapter.persistence.JpaSampleImageStore} owns the mapping in both directions.
 *
 * <p>Bit-for-bit the {@code AssetImageEntity} precedent applied to training frames instead of
 * asset photos: {@code sampleId} is the primary key rather than a synthetic one — there is at
 * most one image per sample and a save is always an upsert, so the schema itself enforces "one
 * row per sample" without a separate unique constraint. {@code data} is a plain {@code byte[]}
 * field (Hibernate's default mapping to Postgres {@code bytea}, no {@code @Lob}/converter
 * needed). No JPA relationship to {@code TrainingSampleEntity} — same "no referential integrity
 * between repositories" convention every other table in this schema already follows.
 */
@Entity
@Table(name = "sample_images")
public class SampleImageEntity {

    @Id
    @Column(name = "sample_id", nullable = false)
    private UUID sampleId;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "data", nullable = false)
    private byte[] data;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA only. */
    protected SampleImageEntity() {
    }

    public SampleImageEntity(UUID sampleId, String contentType, byte[] data, Instant updatedAt) {
        this.sampleId = sampleId;
        this.contentType = contentType;
        this.data = data.clone();
        this.updatedAt = updatedAt;
    }

    public UUID sampleId() {
        return sampleId;
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

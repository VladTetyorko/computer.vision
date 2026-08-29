package com.drones.vision.adapter.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA row for {@code asset_notes} — mirrors {@link com.drones.vision.warehouse.domain.model.AssetNote}
 * field-for-field (docs/plans/active/WAREHOUSE-UX-PLAN.md D7).
 *
 * <p>{@code id} is the domain's own {@code NoteId} — same "real identity, not synthetic" choice as
 * {@link AuditEntryEntity}. No FK to {@code assets}/{@code users} — this schema's standing
 * convention.
 */
@Entity
@Table(name = "asset_notes")
public class AssetNoteEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "author", nullable = false)
    private UUID author;

    @Column(name = "at", nullable = false)
    private Instant at;

    @Column(name = "text", nullable = false, length = 4000)
    private String text;

    /** JPA only. */
    protected AssetNoteEntity() {
    }

    public AssetNoteEntity(UUID id, UUID assetId, UUID author, Instant at, String text) {
        this.id = id;
        this.assetId = assetId;
        this.author = author;
        this.at = at;
        this.text = text;
    }

    public UUID id() {
        return id;
    }

    public UUID assetId() {
        return assetId;
    }

    public UUID author() {
        return author;
    }

    public Instant at() {
        return at;
    }

    public String text() {
        return text;
    }
}

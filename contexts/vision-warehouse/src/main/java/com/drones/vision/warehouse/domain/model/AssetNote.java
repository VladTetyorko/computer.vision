package com.drones.vision.warehouse.domain.model;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;
import java.time.Instant;

/**
 * A free-text note left against an {@link Asset} — crew handover context ("prop nicked on
 * landing, watch it") that is not itself a {@link MaintenanceRecord} and carries no operational
 * consequence.
 *
 * @param id      typed note identity
 * @param assetId the asset this note is about
 * @param author  the user who wrote it
 * @param at      when it was written
 * @param text    the note text; must not be blank
 */
public record AssetNote(NoteId id, AssetId assetId, UserId author, Instant at, String text) {

    public AssetNote {
        if (id == null) {
            throw new IllegalArgumentException("AssetNote id must not be null");
        }
        if (assetId == null) {
            throw new IllegalArgumentException("AssetNote assetId must not be null");
        }
        if (author == null) {
            throw new IllegalArgumentException("AssetNote author must not be null");
        }
        if (at == null) {
            throw new IllegalArgumentException("AssetNote at must not be null");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("AssetNote text must not be blank");
        }
    }
}

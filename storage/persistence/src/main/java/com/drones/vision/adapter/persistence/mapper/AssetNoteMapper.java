package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.AssetNoteEntity;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.warehouse.domain.model.AssetNote;
import com.drones.vision.warehouse.domain.model.NoteId;

/**
 * {@link AssetNote} &harr; {@link AssetNoteEntity} mapping (docs/plans/active/WAREHOUSE-UX-PLAN.md D7).
 */
public final class AssetNoteMapper {

    private AssetNoteMapper() {
    }

    public static AssetNoteEntity toEntity(AssetNote note) {
        return new AssetNoteEntity(note.id().value(), note.assetId().value(), note.author().value(), note.at(),
                note.text());
    }

    public static AssetNote toDomain(AssetNoteEntity entity) {
        return new AssetNote(new NoteId(entity.id()), new AssetId(entity.assetId()), new UserId(entity.author()),
                entity.at(), entity.text());
    }
}

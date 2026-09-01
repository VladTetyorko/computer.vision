package com.drones.vision.warehouse.domain.model;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;

class AssetNoteTest {

    @Test
    void rejectsInvalidArguments() {
        NoteId id = NoteId.random();
        AssetId assetId = AssetId.random();
        UserId author = UserId.random();
        Instant now = Instant.now();

        assertThrows(IllegalArgumentException.class, () -> new AssetNote(null, assetId, author, now, "text"));
        assertThrows(IllegalArgumentException.class, () -> new AssetNote(id, null, author, now, "text"));
        assertThrows(IllegalArgumentException.class, () -> new AssetNote(id, assetId, null, now, "text"));
        assertThrows(IllegalArgumentException.class, () -> new AssetNote(id, assetId, author, null, "text"));
        assertThrows(IllegalArgumentException.class, () -> new AssetNote(id, assetId, author, now, null));
        assertThrows(IllegalArgumentException.class, () -> new AssetNote(id, assetId, author, now, " "));
    }
}

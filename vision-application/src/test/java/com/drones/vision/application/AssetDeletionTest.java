package com.drones.vision.application;

import com.drones.vision.domain.model.AssetId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssetDeletionTest {

    @Test
    void rejectsMissingIdOrBlankName() {
        assertThrows(IllegalArgumentException.class, () -> new AssetDeletion(null, "drone", 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new AssetDeletion(AssetId.random(), " ", 1, 0, 0));
    }

    @Test
    void rejectsNegativeCounts() {
        assertThrows(IllegalArgumentException.class, () -> new AssetDeletion(AssetId.random(), "drone", -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new AssetDeletion(AssetId.random(), "drone", 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new AssetDeletion(AssetId.random(), "drone", 0, 0, -1));
    }

    @Test
    void reportsWhatWasPreservedNotOnlyWhatWasRemoved() {
        AssetDeletion deletion = new AssetDeletion(AssetId.random(), "my drone", 2, 14, 1);

        assertEquals(2, deletion.devicesDeleted());
        assertEquals(14, deletion.usagesRetained());
        assertEquals(1, deletion.streamsStopped());
    }
}

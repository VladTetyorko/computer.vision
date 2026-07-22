package com.drones.vision.application;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssetEditTest {

    @Test
    void everyFieldIsOptionalBecauseNullMeansLeaveUnchanged() {
        AssetEdit edit = new AssetEdit(null, null, null);

        assertNull(edit.displayName());
        assertNull(edit.category());
        assertNull(edit.attributes());
    }

    @Test
    void nothingIsTheIdentityEdit() {
        assertNull(AssetEdit.NOTHING.displayName());
        assertNull(AssetEdit.NOTHING.category());
        assertNull(AssetEdit.NOTHING.attributes());
    }

    @Test
    void rejectsAPresentButBlankDisplayName() {
        assertThrows(IllegalArgumentException.class, () -> new AssetEdit(" ", null, null));
    }

    @Test
    void copiesAPresentAttributeMap() {
        Map<String, String> mutable = new HashMap<>(Map.of("color", "red"));
        AssetEdit edit = new AssetEdit(null, null, mutable);

        mutable.put("color", "blue");

        assertEquals(Map.of("color", "red"), edit.attributes());
    }
}

package com.drones.vision.warehouse.application.asset;

import com.drones.vision.warehouse.domain.model.Identity;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssetEditTest {

    @Test
    void everyFieldIsOptionalBecauseNullMeansLeaveUnchanged() {
        AssetEdit edit = new AssetEdit(null, null, null, null);

        assertNull(edit.displayName());
        assertNull(edit.category());
        assertNull(edit.attributes());
        assertNull(edit.identity());
    }

    @Test
    void nothingIsTheIdentityEdit() {
        assertNull(AssetEdit.NOTHING.displayName());
        assertNull(AssetEdit.NOTHING.category());
        assertNull(AssetEdit.NOTHING.attributes());
        assertNull(AssetEdit.NOTHING.identity());
    }

    @Test
    void rejectsAPresentButBlankDisplayName() {
        assertThrows(IllegalArgumentException.class, () -> new AssetEdit(" ", null, null, null));
    }

    @Test
    void copiesAPresentAttributeMap() {
        Map<String, String> mutable = new HashMap<>(Map.of("color", "red"));
        AssetEdit edit = new AssetEdit(null, null, mutable, null);

        mutable.put("color", "blue");

        assertEquals(Map.of("color", "red"), edit.attributes());
    }

    @Test
    void carriesAPresentIdentity() {
        Identity identity = new Identity("SN-1", "Acme", "X1", "N123AB");

        AssetEdit edit = new AssetEdit(null, null, null, identity);

        assertEquals(identity, edit.identity());
    }
}

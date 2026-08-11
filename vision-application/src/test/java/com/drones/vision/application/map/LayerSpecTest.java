package com.drones.vision.application.map;

import com.drones.vision.kernel.GroupId;
import com.drones.vision.map.domain.model.LayerKind;
import com.drones.vision.map.domain.model.MapLayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class LayerSpecTest {

    @Test
    void rejectsNullKind() {
        assertThrows(IllegalArgumentException.class, () -> new LayerSpec("name", null, null));
    }

    @Test
    void rejectsCopKind() {
        assertThrows(IllegalArgumentException.class,
                () -> new LayerSpec("name", LayerKind.COP, GroupId.random()));
    }

    @Test
    void rejectsTeamWithoutGroupId() {
        assertThrows(IllegalArgumentException.class, () -> new LayerSpec("name", LayerKind.TEAM, null));
    }

    @Test
    void allowsPersonalWithoutGroupId() {
        new LayerSpec("name", LayerKind.PERSONAL, null);
    }

    @Test
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> new LayerSpec(" ", LayerKind.PERSONAL, null));
        assertThrows(IllegalArgumentException.class, () -> new LayerSpec(null, LayerKind.PERSONAL, null));
    }

    @Test
    void rejectsNameOverMaxLength() {
        String tooLong = "x".repeat(MapLayer.MAX_NAME_LENGTH + 1);
        assertThrows(IllegalArgumentException.class, () -> new LayerSpec(tooLong, LayerKind.PERSONAL, null));
    }
}

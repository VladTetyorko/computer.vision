package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeviceCategoryTest {

    @Test
    void attributeHintsAreDefensivelyCopied() {
        List<String> hints = new ArrayList<>(List.of("resolution"));

        DeviceCategory category = new DeviceCategory(new CategoryId("ip-camera"), "IP Camera", null, hints);

        hints.add("fps");

        assertEquals(1, category.attributeHints().size(),
                "later mutation of the source list must not affect the category");
        assertThrows(UnsupportedOperationException.class, () -> category.attributeHints().add("zoom"),
                "returned attributeHints list must be immutable");
    }

    @Test
    void parentIsNullableForTopLevelCategories() {
        DeviceCategory category = new DeviceCategory(new CategoryId("drone"), "Drone", null, List.of());

        assertNull(category.parent());
    }

    @Test
    void acceptsNonNullParent() {
        DeviceCategory category =
                new DeviceCategory(new CategoryId("fpv-drone"), "FPV Drone", new CategoryId("drone"), List.of());

        assertEquals(new CategoryId("drone"), category.parent());
    }

    @Test
    void rejectsInvalidArguments() {
        CategoryId id = new CategoryId("drone");

        assertThrows(IllegalArgumentException.class, () -> new DeviceCategory(null, "Drone", null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new DeviceCategory(id, null, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new DeviceCategory(id, "", null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new DeviceCategory(id, "Drone", null, null));
    }
}

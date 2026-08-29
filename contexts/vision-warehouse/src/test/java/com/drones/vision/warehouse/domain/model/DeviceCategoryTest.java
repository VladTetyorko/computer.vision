package com.drones.vision.warehouse.domain.model;

import com.drones.vision.kernel.CategoryId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceCategoryTest {

    @Test
    void attributeHintsAreDefensivelyCopied() {
        List<String> hints = new ArrayList<>(List.of("resolution"));

        DeviceCategory category = new DeviceCategory(new CategoryId("ip-camera"), "IP Camera", null, hints, true);

        hints.add("fps");

        assertEquals(1, category.attributeHints().size(),
                "later mutation of the source list must not affect the category");
        assertThrows(UnsupportedOperationException.class, () -> category.attributeHints().add("zoom"),
                "returned attributeHints list must be immutable");
    }

    @Test
    void parentIsNullableForTopLevelCategories() {
        DeviceCategory category = new DeviceCategory(new CategoryId("drone"), "Drone", null, List.of(), true);

        assertNull(category.parent());
    }

    @Test
    void acceptsNonNullParent() {
        DeviceCategory category = new DeviceCategory(new CategoryId("fpv-drone"), "FPV Drone",
                new CategoryId("drone"), List.of(), true);

        assertEquals(new CategoryId("drone"), category.parent());
    }

    @Test
    void connectedMarksACategoryAsRequiringAtLeastOneDevice() {
        DeviceCategory drone = new DeviceCategory(new CategoryId("drone"), "Drone", null, List.of(), true);
        DeviceCategory battery = new DeviceCategory(new CategoryId("battery"), "Battery", null, List.of(), false);

        assertTrue(drone.connected());
        assertFalse(battery.connected());
    }

    @Test
    void rejectsInvalidArguments() {
        CategoryId id = new CategoryId("drone");

        assertThrows(IllegalArgumentException.class, () -> new DeviceCategory(null, "Drone", null, List.of(), true));
        assertThrows(IllegalArgumentException.class, () -> new DeviceCategory(id, null, null, List.of(), true));
        assertThrows(IllegalArgumentException.class, () -> new DeviceCategory(id, "", null, List.of(), true));
        assertThrows(IllegalArgumentException.class, () -> new DeviceCategory(id, "Drone", null, null, true));
    }
}

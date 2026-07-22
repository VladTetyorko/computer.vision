package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CategoryIdTest {

    @Test
    void rejectsNullAndBlankSlug() {
        assertThrows(IllegalArgumentException.class, () -> new CategoryId(null));
        assertThrows(IllegalArgumentException.class, () -> new CategoryId(""));
        assertThrows(IllegalArgumentException.class, () -> new CategoryId("   "));
    }

    @Test
    void acceptsLowerCaseKebabSlugs() {
        assertEquals("drone", new CategoryId("drone").slug());
        assertEquals("fpv-drone", new CategoryId("fpv-drone").slug());
        assertEquals("esp32-cam", new CategoryId("esp32-cam").slug());
        assertEquals("a-b-c", new CategoryId("a-b-c").slug());
    }

    @Test
    void rejectsNonKebabCaseSlugs() {
        assertThrows(IllegalArgumentException.class, () -> new CategoryId("FPV-Drone"));
        assertThrows(IllegalArgumentException.class, () -> new CategoryId("fpv_drone"));
        assertThrows(IllegalArgumentException.class, () -> new CategoryId("fpv drone"));
        assertThrows(IllegalArgumentException.class, () -> new CategoryId("-fpv-drone"));
        assertThrows(IllegalArgumentException.class, () -> new CategoryId("fpv-drone-"));
        assertThrows(IllegalArgumentException.class, () -> new CategoryId("fpv--drone"));
        assertThrows(IllegalArgumentException.class, () -> new CategoryId("Drone"));
    }
}

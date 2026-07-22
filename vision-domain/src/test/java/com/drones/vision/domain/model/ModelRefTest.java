package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelRefTest {

    @Test
    void rejectsBlankIdOrVersion() {
        assertThrows(IllegalArgumentException.class, () -> new ModelRef(null, "1"));
        assertThrows(IllegalArgumentException.class, () -> new ModelRef("", "1"));
        assertThrows(IllegalArgumentException.class, () -> new ModelRef("yolo", null));
        assertThrows(IllegalArgumentException.class, () -> new ModelRef("yolo", ""));
    }

    @Test
    void acceptsNonBlankIdAndVersion() {
        new ModelRef("yolo", "latest");
    }
}

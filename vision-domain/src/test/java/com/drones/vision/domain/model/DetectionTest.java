package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class DetectionTest {

    private static BoundingBox box() {
        return new BoundingBox(0.1, 0.1, 0.2, 0.2);
    }

    private static ModelRef model() {
        return new ModelRef("yolo", "1");
    }

    @Test
    void rejectsBlankLabel() {
        assertThrows(IllegalArgumentException.class, () -> new Detection(null, 0.5, box(), model()));
        assertThrows(IllegalArgumentException.class, () -> new Detection("", 0.5, box(), model()));
        assertThrows(IllegalArgumentException.class, () -> new Detection("  ", 0.5, box(), model()));
    }

    @Test
    void rejectsOutOfRangeConfidence() {
        assertThrows(IllegalArgumentException.class, () -> new Detection("person", -0.01, box(), model()));
        assertThrows(IllegalArgumentException.class, () -> new Detection("person", 1.01, box(), model()));
        assertThrows(IllegalArgumentException.class, () -> new Detection("person", Double.NaN, box(), model()));
    }

    @Test
    void rejectsNullBoxOrModel() {
        assertThrows(IllegalArgumentException.class, () -> new Detection("person", 0.5, null, model()));
        assertThrows(IllegalArgumentException.class, () -> new Detection("person", 0.5, box(), null));
    }

    @Test
    void acceptsBoundaryConfidence() {
        new Detection("person", 0.0, box(), model());
        new Detection("person", 1.0, box(), model());
    }
}

package com.drones.vision.kernel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class BoundingBoxTest {

    @Test
    void acceptsBoundaryValues() {
        new BoundingBox(0.0, 0.0, 0.0, 0.0);
        new BoundingBox(1.0, 1.0, 1.0, 1.0);
        new BoundingBox(0.5, 0.25, 0.3, 0.4);
    }

    @Test
    void rejectsOutOfRangeComponents() {
        assertThrows(IllegalArgumentException.class, () -> new BoundingBox(-0.01, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new BoundingBox(1.01, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new BoundingBox(0, -0.01, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new BoundingBox(0, 1.01, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new BoundingBox(0, 0, -0.01, 0));
        assertThrows(IllegalArgumentException.class, () -> new BoundingBox(0, 0, 1.01, 0));
        assertThrows(IllegalArgumentException.class, () -> new BoundingBox(0, 0, 0, -0.01));
        assertThrows(IllegalArgumentException.class, () -> new BoundingBox(0, 0, 0, 1.01));
        assertThrows(IllegalArgumentException.class, () -> new BoundingBox(Double.NaN, 0, 0, 0));
    }
}

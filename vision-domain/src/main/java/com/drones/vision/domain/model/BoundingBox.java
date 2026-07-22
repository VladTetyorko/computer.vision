package com.drones.vision.domain.model;

/**
 * A normalized, axis-aligned bounding box in image coordinates.
 *
 * <p>All four components are fractions of the frame's width/height in the
 * range {@code [0, 1]}, so a box is independent of the pixel resolution it
 * was detected on.
 *
 * @param x      left edge, normalized [0,1]
 * @param y      top edge, normalized [0,1]
 * @param width  box width, normalized [0,1]
 * @param height box height, normalized [0,1]
 */
public record BoundingBox(double x, double y, double width, double height) {

    public BoundingBox {
        requireUnitRange(x, "x");
        requireUnitRange(y, "y");
        requireUnitRange(width, "width");
        requireUnitRange(height, "height");
    }

    private static void requireUnitRange(double value, String name) {
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException("BoundingBox " + name + " must be within [0,1]: " + value);
        }
    }
}

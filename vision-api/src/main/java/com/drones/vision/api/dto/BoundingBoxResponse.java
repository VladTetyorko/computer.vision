package com.drones.vision.api.dto;

import com.drones.vision.kernel.BoundingBox;

/**
 * Wire representation of a {@link BoundingBox}, embedded in {@link DetectionResponse}.
 *
 * @param x      left edge, normalized [0,1] against frame width
 * @param y      top edge, normalized [0,1] against frame height
 * @param width  normalized [0,1] against frame width
 * @param height normalized [0,1] against frame height
 */
public record BoundingBoxResponse(double x, double y, double width, double height) {

    /**
     * Maps a domain {@link BoundingBox} to its wire representation.
     *
     * @param box the box to map
     * @return the response body for {@code box}
     */
    public static BoundingBoxResponse from(BoundingBox box) {
        return new BoundingBoxResponse(box.x(), box.y(), box.width(), box.height());
    }
}

package com.drones.vision.api.dto;

import com.drones.vision.domain.model.BoundingBox;

/**
 * Wire representation of a corrected/drawn {@link BoundingBox}, embedded in {@link
 * AnnotationRequest} (docs/plans/done/CV-TRAINING-PLAN.md §3's frozen wire contract) — the request-side
 * mirror of {@link BoundingBoxResponse}.
 *
 * @param x      left edge, normalized [0,1] against frame width
 * @param y      top edge, normalized [0,1] against frame height
 * @param width  normalized [0,1] against frame width
 * @param height normalized [0,1] against frame height
 */
public record BoundingBoxRequest(double x, double y, double width, double height) {

    /**
     * Maps this request to a domain {@link BoundingBox}.
     *
     * @return the domain box
     * @throws IllegalArgumentException if any component is outside {@code [0,1]} ({@link
     *                                   BoundingBox}'s own compact constructor)
     */
    public BoundingBox toBoundingBox() {
        return new BoundingBox(x, y, width, height);
    }
}

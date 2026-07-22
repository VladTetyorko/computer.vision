package com.drones.vision.domain.model;

/**
 * A single object detection produced by a model on one frame.
 *
 * @param label      class label (e.g. {@code "person"}); must not be blank
 * @param confidence detection confidence, range [0,1]
 * @param box        normalized bounding box
 * @param model      model that produced this detection
 */
public record Detection(String label, double confidence, BoundingBox box, ModelRef model) {

    public Detection {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("Detection label must not be blank");
        }
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("Detection confidence must be within [0,1]: " + confidence);
        }
        if (box == null) {
            throw new IllegalArgumentException("Detection box must not be null");
        }
        if (model == null) {
            throw new IllegalArgumentException("Detection model must not be null");
        }
    }
}

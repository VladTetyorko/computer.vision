package com.drones.vision.api.dto;

import com.drones.vision.domain.model.Detection;

/**
 * Response body element for one detected object, embedded in {@link DetectionResultResponse}.
 *
 * @param label      the detected class label
 * @param confidence [0,1]
 * @param box        the detection's bounding box, normalized against frame dimensions
 * @param modelId    the model that produced this detection
 * @param modelVersion the model's version string
 */
public record DetectionResponse(String label, double confidence, BoundingBoxResponse box, String modelId,
                                 String modelVersion) {

    /**
     * Maps a domain {@link Detection} to its wire representation.
     *
     * @param detection the detection to map
     * @return the response body for {@code detection}
     */
    public static DetectionResponse from(Detection detection) {
        return new DetectionResponse(detection.label(), detection.confidence(),
                BoundingBoxResponse.from(detection.box()), detection.model().id(), detection.model().version());
    }
}

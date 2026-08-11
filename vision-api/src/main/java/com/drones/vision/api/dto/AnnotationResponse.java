package com.drones.vision.api.dto;

import com.drones.vision.learning.domain.model.Annotation;

/**
 * Wire representation of one {@link Annotation}, embedded in {@link SampleResponse}
 * (docs/plans/done/CV-TRAINING-PLAN.md §3's frozen wire contract).
 *
 * @param label  class label
 * @param source {@code "MODEL"} or {@code "OPERATOR"} — the annotation's {@link
 *               com.drones.vision.learning.domain.model.AnnotationSource}
 * @param box    the annotated region, normalized [0,1], top-left origin
 */
public record AnnotationResponse(String label, String source, BoundingBoxResponse box) {

    /**
     * Maps a domain {@link Annotation} to its wire representation.
     *
     * @param annotation the annotation to map
     * @return the response body for {@code annotation}
     */
    public static AnnotationResponse from(Annotation annotation) {
        return new AnnotationResponse(annotation.label(), annotation.source().name(),
                BoundingBoxResponse.from(annotation.box()));
    }
}

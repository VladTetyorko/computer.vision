package com.drones.vision.api.dto;

import com.drones.vision.domain.model.Annotation;
import com.drones.vision.domain.model.AnnotationSource;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Wire representation of one corrected/confirmed {@link Annotation}, embedded in {@link
 * LabelAnnotationsRequest} (docs/plans/done/CV-TRAINING-PLAN.md §3's frozen wire contract, body of {@code PUT
 * /api/samples/{id}/annotations}).
 *
 * @param label  class label
 * @param source provenance, matched case-insensitively against {@link AnnotationSource} names
 *               (e.g. {@code "MODEL"}/{@code "OPERATOR"})
 * @param box    the annotated region, normalized [0,1], top-left origin
 */
public record AnnotationRequest(String label, String source, BoundingBoxRequest box) {

    /**
     * Maps this request to a domain {@link Annotation}.
     *
     * @return the domain annotation
     * @throws IllegalArgumentException if {@link #label()} is blank ({@link Annotation}'s own
     *                                   compact constructor), {@link #source()} is not a
     *                                   recognized {@link AnnotationSource} name, or {@link
     *                                   #box()} is missing/invalid
     */
    public Annotation toAnnotation() {
        if (box == null) {
            throw new IllegalArgumentException("Annotation box must not be null");
        }
        return new Annotation(label, box.toBoundingBox(), toSource());
    }

    private AnnotationSource toSource() {
        if (source != null) {
            for (AnnotationSource candidate : AnnotationSource.values()) {
                if (candidate.name().equalsIgnoreCase(source)) {
                    return candidate;
                }
            }
        }
        throw new IllegalArgumentException("Unknown annotation source: " + source + " (valid values: "
                + Arrays.stream(AnnotationSource.values()).map(Enum::name).collect(Collectors.joining(", ")) + ")");
    }
}

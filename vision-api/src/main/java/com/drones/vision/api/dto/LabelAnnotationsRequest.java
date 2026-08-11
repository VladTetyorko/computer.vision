package com.drones.vision.api.dto;

import com.drones.vision.application.training.LabelSpec;
import com.drones.vision.learning.domain.model.Annotation;
import com.drones.vision.learning.domain.model.SampleStatus;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Request body for {@code PUT /api/samples/{id}/annotations} (docs/plans/done/CV-TRAINING-PLAN.md §3's frozen
 * wire contract) — the operator's confirmed/corrected annotations plus the review outcome.
 *
 * @param status      {@code "LABELED"} or {@code "DISCARDED"}, matched case-insensitively
 * @param annotations the corrected ground-truth annotations replacing the sample's current ones;
 *                    absent/{@code null} defaults to an empty list (a confirmed negative/background
 *                    sample)
 */
public record LabelAnnotationsRequest(String status, List<AnnotationRequest> annotations) {

    /**
     * Maps this request to a {@link LabelSpec}.
     *
     * @return the command record for {@link com.drones.vision.application.training.LabelingService#label}
     * @throws IllegalArgumentException if {@link #status()} is not {@code LABELED}/{@code
     *                                   DISCARDED} ({@link LabelSpec}'s own check, backstopping
     *                                   this class's own case-insensitive match), or any
     *                                   annotation is malformed ({@link
     *                                   AnnotationRequest#toAnnotation()})
     */
    public LabelSpec toSpec() {
        List<Annotation> mapped = (annotations == null ? List.<AnnotationRequest>of() : annotations).stream()
                .map(AnnotationRequest::toAnnotation).toList();
        return new LabelSpec(mapped, toStatus());
    }

    private SampleStatus toStatus() {
        if (status != null) {
            for (SampleStatus candidate : SampleStatus.values()) {
                if (candidate.name().equalsIgnoreCase(status)) {
                    return candidate;
                }
            }
        }
        throw new IllegalArgumentException("Unknown status: " + status + " (valid values: "
                + Arrays.stream(SampleStatus.values()).map(Enum::name).collect(Collectors.joining(", ")) + ")");
    }
}

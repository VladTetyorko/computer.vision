package com.drones.vision.learning.application;

import com.drones.vision.learning.domain.model.Annotation;
import com.drones.vision.learning.domain.model.SampleStatus;

import java.util.List;
import java.util.Objects;

/**
 * {@link LabelingService#label}'s command record (docs/plans/done/CV-TRAINING-PLAN.md §2/§3) — the wire
 * shape for {@code PUT /api/samples/{id}/annotations}: the operator's confirmed/corrected
 * ground-truth annotations plus the review outcome.
 *
 * <p>{@code status} must be {@link SampleStatus#LABELED} or {@link SampleStatus#DISCARDED} — the
 * only two outcomes a review can end in ({@link SampleStatus#PENDING} is capture's own starting
 * state, never a target one reaches back to via this command). Rejecting anything else here, at
 * the wire boundary, means {@link DefaultLabelingService#label} never has to guard against a
 * nonsensical target status itself. Whether the sample being labeled is currently {@code PENDING},
 * already {@code LABELED} (a re-correction), or already {@code DISCARDED} (an operator changing
 * their mind) is not restricted here — see {@link LabelingService#label}'s own javadoc.
 *
 * @param annotations the corrected ground-truth annotations replacing the sample's current ones;
 *                    must not be {@code null} (may be empty — a confirmed negative/background
 *                    sample); defensively copied
 * @param status      the review outcome; must be {@link SampleStatus#LABELED} or
 *                    {@link SampleStatus#DISCARDED}
 */
public record LabelSpec(List<Annotation> annotations, SampleStatus status) {

    public LabelSpec {
        Objects.requireNonNull(annotations, "annotations must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (status != SampleStatus.LABELED && status != SampleStatus.DISCARDED) {
            throw new IllegalArgumentException("LabelSpec status must be LABELED or DISCARDED, got: " + status);
        }
        annotations = List.copyOf(annotations);
    }
}

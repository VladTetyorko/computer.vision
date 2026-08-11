package com.drones.vision.domain.model;

/**
 * One ground-truth box on a captured frame (docs/plans/done/CV-TRAINING-PLAN.md §1) — ready to export into a
 * YOLO label line.
 *
 * <p>Reuses {@link BoundingBox} rather than inventing a second box type; unlike {@link Detection},
 * there is deliberately <b>no confidence</b> here — this is a human-confirmed truth, not a
 * model's guess. {@code label} is a free-form string (matching {@link Detection#label()}), not a
 * {@link CategoryId} — open-vocab detection labels (e.g. {@code "skyscraper"}) aren't categories,
 * so {@link Dataset#classes()} stays free-form too (docs/plans/done/CV-TRAINING-PLAN.md Open Questions §5).
 * Whether a sample's annotation labels are members of its dataset's {@link Dataset#classes()} is
 * an application-layer check ({@code LabelingService.label}), not enforced here.
 *
 * @param label  class label; must not be blank
 * @param box    the annotated region, normalized [0,1]
 * @param source whether this annotation came from a kept model detection or was drawn/edited by
 *               the operator
 */
public record Annotation(String label, BoundingBox box, AnnotationSource source) {

    public Annotation {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("Annotation label must not be blank");
        }
        if (box == null) {
            throw new IllegalArgumentException("Annotation box must not be null");
        }
        if (source == null) {
            throw new IllegalArgumentException("Annotation source must not be null");
        }
    }
}

package com.drones.vision.learning.domain.model;

/**
 * A model's reported accuracy figure, labelled by where it came from
 * (docs/plans/active/CV-SETTINGS-PLAN.md §3.2, §3.5 honesty rule 5) — {@code map50} alone, with no
 * {@link MetricsKind}, would let a training-time number be mistaken for a held-out evaluation;
 * bundling the two together makes the provenance impossible to drop when the figure is displayed.
 *
 * @param map50 mAP@0.5 reported for this model, or {@code null} if none has been measured/reported
 *              yet
 * @param kind  which stage produced (or will produce) {@code map50}; must not be {@code null}
 */
public record ModelMetrics(Double map50, MetricsKind kind) {

    public ModelMetrics {
        if (kind == null) {
            throw new IllegalArgumentException("ModelMetrics kind must not be null");
        }
    }
}

package com.drones.vision.api.dto;

import com.drones.vision.learning.domain.model.ModelMetrics;

/**
 * {@code CvModelResponse#metrics} — a model's reported accuracy figure, labelled by where it came
 * from (docs/plans/active/CV-SETTINGS-PLAN.md §3.2/§3.5 honesty rule 5). No {@code
 * @JsonInclude(NON_NULL)} here (unlike the enclosing {@link CvModelResponse}, which omits this whole
 * object when {@code null}): once a {@code metrics} object is present, {@link #map50()} still
 * serializes as explicit {@code null} rather than being dropped when the domain {@link
 * ModelMetrics#map50()} itself is {@code null} — "no figure measured yet" is a real, distinct fact
 * from "no metrics object at all".
 *
 * @param map50 mAP@0.5 reported for this model, or {@code null} if none has been measured/reported
 *              yet
 * @param kind  {@code TRAINING} or {@code WORKER} (the domain {@code MetricsKind} name verbatim —
 *              {@code WORKER} has no counterpart in the frozen TS {@code CvModelMetrics#kind} union,
 *              a documented, harmless gap since that domain value is never actually produced yet,
 *              per docs/plans/active/CV-SETTINGS-CONTEXT.md's W4-app handoff)
 */
public record CvModelMetricsResponse(Double map50, String kind) {

    /**
     * @param metrics the domain metrics, or {@code null}
     * @return the wire representation, or {@code null} when {@code metrics} is {@code null}
     */
    public static CvModelMetricsResponse from(ModelMetrics metrics) {
        if (metrics == null) {
            return null;
        }
        return new CvModelMetricsResponse(metrics.map50(), metrics.kind().name());
    }
}

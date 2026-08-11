package com.drones.vision.api.dto;

import com.drones.vision.domain.model.ModelRef;
import com.drones.vision.api.exception.ApiExceptionHandler;

/**
 * Request body for {@code POST /api/cv/registry/models/{id}/promote} (docs/plans/done/CV-TRAINING-PLAN.md
 * §8) — the {@code id} itself comes from the path; a {@link ModelRef} needs both {@code id} and
 * {@code version} to resolve one specific model, so {@code version} travels in the body.
 *
 * @param version the model version to promote; must not be blank ({@link ModelRef}'s own compact
 *                constructor check, surfaced as {@code 400} via {@code ApiExceptionHandler})
 */
public record PromoteModelRequest(String version) {
}

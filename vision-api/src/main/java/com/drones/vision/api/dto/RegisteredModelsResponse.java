package com.drones.vision.api.dto;

import java.util.List;

/**
 * Response body for {@code GET /api/cv/registry/models} (docs/CV-TRAINING-PLAN.md §8) — the whole
 * known model roster, marking which one (if any) is currently active.
 *
 * @param models the registry snapshot, in {@link com.drones.vision.application.ModelRegistryService#models()}'s order
 */
public record RegisteredModelsResponse(List<RegisteredModelResponse> models) {

    public RegisteredModelsResponse {
        models = List.copyOf(models);
    }
}

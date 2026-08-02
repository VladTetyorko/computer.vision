package com.drones.vision.api.dto;

import java.util.List;

/**
 * Wrapper body for {@code GET /api/datasets/{id}/samples} (docs/CV-TRAINING-PLAN.md §3's frozen
 * wire contract) — mirrors {@link CvModelsResponse}'s wrapped-list shape, same reasoning as {@link
 * DatasetsResponse}.
 *
 * @param samples a snapshot of matching samples, in {@link
 *                com.drones.vision.application.training.LabelingService#samples}'s own order
 */
public record SamplesResponse(List<SampleResponse> samples) {
}

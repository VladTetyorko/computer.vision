package com.drones.vision.api.dto;

import java.util.List;

/**
 * Response body for {@code GET /api/cv/training/runs} (docs/plans/active/CV-SETTINGS-PLAN.md
 * &sect;5.2's frozen wire contract; {@code models.ts}'s own comment above its {@code
 * TrainingRunsResponse} says the path is {@code GET /api/cv/training-runs} — that comment is stale,
 * the plan's own &sect;5.2 endpoint table is authoritative, see the W5 handoff note in
 * docs/plans/active/CV-SETTINGS-CONTEXT.md) — mirrors {@link CvModelsResponse}'s wrapped-list shape.
 *
 * @param runs every persisted run {@link com.drones.vision.learning.application.TrainingJobService#runs}
 *             returns, newest-first
 */
public record TrainingRunsResponse(List<TrainingRunResponse> runs) {

    public TrainingRunsResponse {
        runs = List.copyOf(runs);
    }
}

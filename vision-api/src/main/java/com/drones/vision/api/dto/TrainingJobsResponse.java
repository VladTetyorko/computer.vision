package com.drones.vision.api.dto;

import java.util.List;

/**
 * Response body for {@code GET /api/training/jobs} (docs/plans/done/CV-TRAINING-PLAN.md §8's frozen wire
 * contract) — every tracked job, mirroring {@link RegisteredModelsResponse}'s wrapped-list shape.
 *
 * @param jobs every job {@link com.drones.vision.application.training.TrainingJobService#jobs()} still
 *             tracks, in that method's own order (newest-first by {@code startedAt})
 */
public record TrainingJobsResponse(List<TrainingJobResponse> jobs) {

    public TrainingJobsResponse {
        jobs = List.copyOf(jobs);
    }
}

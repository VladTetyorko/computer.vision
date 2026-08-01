package com.drones.vision.api.dto;

import com.drones.vision.domain.model.DatasetId;

/**
 * Request body for {@code POST /api/usages/{usageId}/samples} (docs/CV-TRAINING-V2-PLAN.md §5) —
 * the usage id itself is a path parameter, so the body carries only the target dataset and the
 * replay offset to capture from.
 *
 * @param datasetId the dataset to add the captured sample to, as a canonical UUID string
 * @param atSeconds offset from the usage's own {@code startedAt}, in seconds; must be finite and
 *                  {@code >= 0} — validated by {@code ReplayCaptureSpec}'s own compact constructor,
 *                  not here
 */
public record CaptureFromReplayRequest(String datasetId, double atSeconds) {

    /**
     * Parses {@link #datasetId()}.
     *
     * @return the parsed {@link DatasetId}
     * @throws IllegalArgumentException if {@link #datasetId()} is missing/blank or not a valid
     *                                   UUID
     */
    public DatasetId toDatasetId() {
        if (datasetId == null || datasetId.isBlank()) {
            throw new IllegalArgumentException("datasetId must not be blank");
        }
        return DatasetId.of(datasetId);
    }
}

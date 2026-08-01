package com.drones.vision.api.dto;

import com.drones.vision.domain.model.DatasetId;

/**
 * Request body for {@code POST /api/streams/{streamId}/samples} (docs/CV-TRAINING-PLAN.md §3's
 * frozen wire contract) — the stream id itself is a path parameter, so the body carries only the
 * target dataset.
 *
 * @param datasetId the dataset to add the captured sample to, as a canonical UUID string
 */
public record CaptureSampleRequest(String datasetId) {

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

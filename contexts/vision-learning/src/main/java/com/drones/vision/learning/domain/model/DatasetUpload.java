package com.drones.vision.learning.domain.model;

import java.time.Instant;

/**
 * What one completed {@code DatasetUploadPort.upload} run delivered to the training host
 * (docs/plans/done/CV-TRAINING-V2-PLAN.md §3) — the replacement for {@code DatasetExport}: a delivery
 * receipt for a gRPC upload rather than a manifest for a filesystem artifact.
 *
 * @param datasetId   the dataset that was uploaded
 * @param uploadedAt  when the upload completed
 * @param sampleCount number of samples included in the upload; must not be negative
 * @param sizeBytes   total size of the uploaded archive in bytes; must not be negative
 */
public record DatasetUpload(DatasetId datasetId, Instant uploadedAt, int sampleCount, long sizeBytes) {

    public DatasetUpload {
        if (datasetId == null) {
            throw new IllegalArgumentException("DatasetUpload datasetId must not be null");
        }
        if (uploadedAt == null) {
            throw new IllegalArgumentException("DatasetUpload uploadedAt must not be null");
        }
        if (sampleCount < 0) {
            throw new IllegalArgumentException("DatasetUpload sampleCount must not be negative: " + sampleCount);
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("DatasetUpload sizeBytes must not be negative: " + sizeBytes);
        }
    }
}

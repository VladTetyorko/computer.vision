package com.drones.vision.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * The manifest of one completed export of a {@link Dataset} (docs/CV-TRAINING-PLAN.md §1/§5) —
 * what {@code DatasetExportPort.write} produced, for download or handing to a fine-tune.
 *
 * @param datasetId  the exported dataset
 * @param exportId   identifier for this specific export run; must not be blank (opaque — an
 *                   implementation detail of the {@code DatasetExportPort} that produced it, not a
 *                   typed domain id, since nothing else references an export by it)
 * @param exportedAt when this export completed
 * @param classes    the YOLO class list this export was written against, in {@code data.yaml}
 *                   order; defensively copied
 * @param sampleCount number of {@link SampleStatus#LABELED} samples included; must not be negative
 * @param sizeBytes  total size of the exported artifact in bytes; must not be negative
 * @param location   where the export lives (an implementation-defined handle, e.g. a filesystem
 *                   path or archive key); must not be blank
 */
public record DatasetExport(DatasetId datasetId, String exportId, Instant exportedAt, List<String> classes,
                             int sampleCount, long sizeBytes, String location) {

    public DatasetExport {
        if (datasetId == null) {
            throw new IllegalArgumentException("DatasetExport datasetId must not be null");
        }
        if (exportId == null || exportId.isBlank()) {
            throw new IllegalArgumentException("DatasetExport exportId must not be blank");
        }
        if (exportedAt == null) {
            throw new IllegalArgumentException("DatasetExport exportedAt must not be null");
        }
        if (classes == null) {
            throw new IllegalArgumentException("DatasetExport classes must not be null");
        }
        if (sampleCount < 0) {
            throw new IllegalArgumentException("DatasetExport sampleCount must not be negative: " + sampleCount);
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("DatasetExport sizeBytes must not be negative: " + sizeBytes);
        }
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("DatasetExport location must not be blank");
        }
        classes = List.copyOf(classes);
    }
}

package com.drones.vision.api.dto;

import com.drones.vision.domain.model.DatasetExport;

import java.time.Instant;
import java.util.List;

/**
 * Wire representation of a {@link DatasetExport} (docs/CV-TRAINING-PLAN.md §3's frozen wire
 * contract), body of {@code POST /api/datasets/{id}/export}.
 *
 * @param datasetId   the exported dataset, as its canonical UUID string
 * @param exportId    this export's own id
 * @param exportedAt  when this export completed
 * @param classes     the YOLO class list this export was written against, in {@code data.yaml}
 *                    order
 * @param sampleCount number of {@code LABELED} samples included
 * @param sizeBytes   total size of the exported artifact in bytes
 * @param downloadUrl where to {@code GET} the exported zip ({@code
 *                    /api/datasets/<datasetId>/export/<exportId>} — not part of {@link
 *                    DatasetExport} itself, composed here since only this module knows its own
 *                    route)
 */
public record DatasetExportResponse(String datasetId, String exportId, Instant exportedAt, List<String> classes,
                                     int sampleCount, long sizeBytes, String downloadUrl) {

    /**
     * Maps a domain {@link DatasetExport} to its wire representation.
     *
     * @param export the export manifest to map
     * @return the response body for {@code export}
     */
    public static DatasetExportResponse from(DatasetExport export) {
        String datasetId = export.datasetId().value().toString();
        return new DatasetExportResponse(datasetId, export.exportId(), export.exportedAt(), export.classes(),
                export.sampleCount(), export.sizeBytes(),
                "/api/datasets/" + datasetId + "/export/" + export.exportId());
    }
}

package com.drones.vision.domain.port.out;

import com.drones.vision.domain.model.DatasetId;
import com.drones.vision.domain.model.DatasetUpload;

import java.util.List;

/**
 * Driven port: ship a YOLO-format dataset (docs/CV-TRAINING-PLAN.md §5) to the training host,
 * replacing any prior upload for the same {@code datasetId} (docs/CV-TRAINING-V2-PLAN.md §3) —
 * the replacement for {@code DatasetExportPort}: delivery to cv-service rides a gRPC upload RPC
 * instead of landing a zip on the platform's own disk.
 *
 * <p>The application layer composes the {@link ExportEntry} list — one per uploaded sample, plus
 * the {@code data.yaml} class list — entirely in memory and with no I/O of its own ({@code
 * YoloDatasetWriter}, pure and unit-testable); this port only frames and transports the bytes.
 * Framing (e.g. a streamed zip archive over a gRPC client-streaming call) is an implementation
 * detail this port hides.
 *
 * <h2>Contract</h2>
 * {@link #upload} delivers one dataset upload and returns a receipt. Uploading the same {@code
 * datasetId} again fully replaces whatever the training host had before — the idempotency the
 * "label more, train again" loop needs. Each call is independent; this port has no notion of a
 * partial or resumable upload.
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use — uploads for different datasets may run
 * concurrently. A single {@code upload} call is not itself meant to be invoked concurrently for
 * the same {@code datasetId}.
 */
public interface DatasetUploadPort {

    /**
     * Uploads a YOLO-format dataset to the training host, replacing any prior upload for the same
     * dataset.
     *
     * @param datasetId the dataset being uploaded
     * @param dataYaml  the {@code data.yaml} file content to ship alongside the images/labels
     * @param entries   one entry per uploaded sample (image bytes + its label file content)
     * @return a receipt describing the completed upload
     */
    DatasetUpload upload(DatasetId datasetId, String dataYaml, List<ExportEntry> entries);

    /**
     * One image plus its YOLO label file content, ready to frame under {@code images/}/{@code
     * labels/} (docs/CV-TRAINING-PLAN.md §5) — the shape {@code DatasetExportPort.ExportEntry} had,
     * carried over verbatim, same three compact-constructor checks.
     *
     * @param imageName     the image's basename, e.g. {@code "<sampleId>.jpg"} — the same stem is
     *                      used for its {@code .txt} label file
     * @param imageBytes    the encoded image bytes to send verbatim; must not be empty
     * @param labelFileText the YOLO label file's full text content — one {@code <class_index> <cx>
     *                      <cy> <w> <h>} line per annotation, or empty for a sample with zero
     *                      annotations (a valid YOLO "negative"/background image)
     */
    record ExportEntry(String imageName, byte[] imageBytes, String labelFileText) {

        public ExportEntry {
            if (imageName == null || imageName.isBlank()) {
                throw new IllegalArgumentException("ExportEntry imageName must not be blank");
            }
            if (imageBytes == null || imageBytes.length == 0) {
                throw new IllegalArgumentException("ExportEntry imageBytes must not be empty");
            }
            if (labelFileText == null) {
                throw new IllegalArgumentException("ExportEntry labelFileText must not be null");
            }
        }
    }
}

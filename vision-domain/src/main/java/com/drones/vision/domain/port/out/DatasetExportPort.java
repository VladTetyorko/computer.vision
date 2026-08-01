package com.drones.vision.domain.port.out;

import com.drones.vision.domain.model.DatasetExport;
import com.drones.vision.domain.model.DatasetId;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Driven port: writes a YOLO-format dataset (docs/CV-TRAINING-PLAN.md §5) to a durable location
 * and returns its manifest.
 *
 * <p>The application layer composes the {@link ExportEntry} list — one per exported sample, plus
 * the {@code data.yaml} class list — entirely in memory and with no I/O of its own ({@code
 * YoloDatasetWriter}, pure and unit-testable); this port only sinks the bytes an implementation
 * decides how to lay out (filesystem tree, zip archive, object storage — an implementation
 * detail this port hides) and hands back a handle to it.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #write} persists one export run for {@code datasetId} and returns its manifest.
 *       Each call is a new, independent export — this port has no notion of updating a previous
 *       export in place.</li>
 *   <li>{@link #resolve} looks up a previously written export's downloadable location by the
 *       {@link DatasetExport#exportId()} {@link #write} returned; {@link Optional#empty()} for an
 *       unknown {@code (datasetId, exportId)} pair, never an error.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use — exports for different datasets (or repeat
 * exports of the same dataset) may run concurrently with downloads of earlier exports.
 */
public interface DatasetExportPort {

    /**
     * Writes a YOLO-format dataset and returns its manifest.
     *
     * @param datasetId the dataset being exported
     * @param classes   the ordered YOLO class list to write into {@code data.yaml}
     * @param entries   one entry per exported sample (image bytes + its label file text)
     * @return the manifest of the completed export
     */
    DatasetExport write(DatasetId datasetId, List<String> classes, List<ExportEntry> entries);

    /**
     * Resolves a previously written export's downloadable location.
     *
     * @param datasetId the dataset the export belongs to
     * @param exportId  the export's own id, as returned by {@link #write} on {@link
     *                  DatasetExport#exportId()}
     * @return the export's location on disk, or {@link Optional#empty()} if no such export exists
     */
    Optional<Path> resolve(DatasetId datasetId, String exportId);

    /**
     * One image plus its YOLO label file content, ready to write under {@code images/}/{@code
     * labels/} (docs/CV-TRAINING-PLAN.md §5).
     *
     * @param imageName     the image's basename, e.g. {@code "<sampleId>.jpg"} — the same stem is
     *                      used for its {@code .txt} label file
     * @param imageBytes    the encoded image bytes to write verbatim; must not be empty
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

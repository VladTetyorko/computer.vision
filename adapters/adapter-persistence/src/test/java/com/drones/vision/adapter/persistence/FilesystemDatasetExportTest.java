package com.drones.vision.adapter.persistence;

import com.drones.vision.domain.model.DatasetExport;
import com.drones.vision.domain.model.DatasetId;
import com.drones.vision.domain.port.out.DatasetExportPort.ExportEntry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure filesystem tests for {@link FilesystemDatasetExport} — no Docker/Postgres needed (this
 * port has nothing to do with the database), so these run unconditionally (docs/CV-TRAINING-PLAN.md
 * §1/§5, Wave T3).
 */
class FilesystemDatasetExportTest {

    @TempDir
    Path exportRoot;

    @Test
    void resolveOnUnknownDatasetOrExportReturnsEmpty() {
        FilesystemDatasetExport export = new FilesystemDatasetExport(exportRoot);

        assertTrue(export.resolve(DatasetId.random(), "nope").isEmpty());
    }

    @Test
    void writeProducesADownloadableZipWithTheFrozenYoloLayout() throws IOException {
        FilesystemDatasetExport export = new FilesystemDatasetExport(exportRoot);
        DatasetId datasetId = DatasetId.random();
        List<String> classes = List.of("building", "tower");
        byte[] imageBytes = {1, 2, 3, 4};
        List<ExportEntry> entries = List.of(
                new ExportEntry("sample-1.jpg", imageBytes, "0 0.25 0.325 0.3 0.25\n"));

        DatasetExport manifest = export.write(datasetId, classes, entries);

        assertEquals(datasetId, manifest.datasetId());
        assertNotNull(manifest.exportId());
        assertEquals(classes, manifest.classes());
        assertEquals(1, manifest.sampleCount());
        assertTrue(manifest.sizeBytes() > 0);

        Optional<Path> resolved = export.resolve(datasetId, manifest.exportId());
        assertTrue(resolved.isPresent());
        assertEquals(manifest.location(), resolved.get().toString());

        try (ZipFile zip = new ZipFile(resolved.get().toFile())) {
            ZipEntry dataYaml = zip.getEntry("data.yaml");
            assertNotNull(dataYaml, "data.yaml must be present in the export archive");
            String yamlText = new String(zip.getInputStream(dataYaml).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(yamlText.contains("names: [building, tower]"));
            assertTrue(yamlText.contains("nc: 2"));
            assertTrue(yamlText.contains("train: images"));
            assertTrue(yamlText.contains("val: images"));

            ZipEntry image = zip.getEntry("images/sample-1.jpg");
            assertNotNull(image, "images/sample-1.jpg must be present");
            assertArrayEquals(imageBytes, zip.getInputStream(image).readAllBytes());

            ZipEntry label = zip.getEntry("labels/sample-1.txt");
            assertNotNull(label, "labels/sample-1.txt must be present, same stem as the image");
            assertEquals("0 0.25 0.325 0.3 0.25\n",
                    new String(zip.getInputStream(label).readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void writeWithNoEntriesStillProducesAValidArchiveWithJustTheManifest() throws IOException {
        FilesystemDatasetExport export = new FilesystemDatasetExport(exportRoot);
        DatasetId datasetId = DatasetId.random();

        DatasetExport manifest = export.write(datasetId, List.of("building"), List.of());

        assertEquals(0, manifest.sampleCount());
        try (ZipFile zip = new ZipFile(export.resolve(datasetId, manifest.exportId()).orElseThrow().toFile())) {
            assertNotNull(zip.getEntry("data.yaml"));
        }
    }

    @Test
    void repeatedWritesForTheSameDatasetProduceIndependentExports() {
        FilesystemDatasetExport export = new FilesystemDatasetExport(exportRoot);
        DatasetId datasetId = DatasetId.random();

        DatasetExport first = export.write(datasetId, List.of("a"), List.of());
        DatasetExport second = export.write(datasetId, List.of("a"), List.of());

        assertNotEquals(first.exportId(), second.exportId());
        assertTrue(export.resolve(datasetId, first.exportId()).isPresent());
        assertTrue(export.resolve(datasetId, second.exportId()).isPresent());
    }
}

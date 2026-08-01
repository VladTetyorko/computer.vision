package com.drones.vision.adapter.persistence;

import com.drones.vision.domain.model.DatasetExport;
import com.drones.vision.domain.model.DatasetId;
import com.drones.vision.domain.port.out.DatasetExportPort;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * {@link DatasetExportPort} writing a real Ultralytics YOLO dataset (docs/CV-TRAINING-PLAN.md §5)
 * to a zip file on the local filesystem.
 *
 * <p>Lives in {@code adapter-persistence} — the plan's own recommendation (docs/CV-TRAINING-PLAN.md
 * Open Questions §1) — because this is the module that already owns every other "user data
 * storage" concern (bytea image bytes, jsonb columns, Flyway-migrated schema). The pure YOLO
 * string-serialization ({@code YoloDatasetWriter}: which sample maps to which {@code
 * ExportEntry}, top-left→center box math, {@code data.yaml} composition) stays in {@code
 * vision-application} (Wave T2) — this class only sinks the bytes an already-built {@link
 * ExportEntry} list carries, exactly as {@link DatasetExportPort}'s own javadoc describes the
 * split.
 *
 * <p>Each {@link #write} call produces exactly one file, {@code
 * <exportRoot>/<datasetId>/<exportId>.zip}, containing the frozen layout as zip entries —
 * {@code data.yaml}, {@code images/<name>}, {@code labels/<name>} — built directly with {@link
 * ZipOutputStream} rather than first writing a loose directory tree and zipping it afterward:
 * the only reader this port's contract defines is {@link #resolve}, which only ever needs to
 * hand back the zip itself (the download endpoint streams {@code application/zip} verbatim), so
 * writing a parallel unzipped copy nobody reads would just double on-disk size for every export.
 */
public final class FilesystemDatasetExport implements DatasetExportPort {

    private final Path exportRoot;

    public FilesystemDatasetExport(Path exportRoot) {
        this.exportRoot = exportRoot;
    }

    @Override
    public DatasetExport write(DatasetId datasetId, List<String> classes, List<ExportEntry> entries) {
        String exportId = UUID.randomUUID().toString();
        Path datasetDir = exportRoot.resolve(datasetId.value().toString());
        Path zipPath = datasetDir.resolve(exportId + ".zip");

        try {
            Files.createDirectories(datasetDir);
            writeZip(zipPath, classes, entries);
            return new DatasetExport(datasetId, exportId, Instant.now(), classes, entries.size(),
                    Files.size(zipPath), zipPath.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to write dataset export " + exportId + " for dataset " + datasetId, e);
        }
    }

    @Override
    public Optional<Path> resolve(DatasetId datasetId, String exportId) {
        Path zipPath = exportRoot.resolve(datasetId.value().toString()).resolve(exportId + ".zip");
        return Files.exists(zipPath) ? Optional.of(zipPath) : Optional.empty();
    }

    private static void writeZip(Path zipPath, List<String> classes, List<ExportEntry> entries)
            throws IOException {
        try (OutputStream fileOut = Files.newOutputStream(zipPath);
             ZipOutputStream zipOut = new ZipOutputStream(fileOut)) {
            writeEntry(zipOut, "data.yaml", dataYaml(classes));
            for (ExportEntry entry : entries) {
                writeEntry(zipOut, "images/" + entry.imageName(), entry.imageBytes());
                writeEntry(zipOut, "labels/" + labelName(entry.imageName()), entry.labelFileText());
            }
        }
    }

    private static void writeEntry(ZipOutputStream zipOut, String name, String text) throws IOException {
        writeEntry(zipOut, name, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeEntry(ZipOutputStream zipOut, String name, byte[] bytes) throws IOException {
        zipOut.putNextEntry(new ZipEntry(name));
        zipOut.write(bytes);
        zipOut.closeEntry();
    }

    /** {@code names: [a, b]}, {@code nc: 2}, {@code train}/{@code val} both {@code images} (docs/CV-TRAINING-PLAN.md §5). */
    private static String dataYaml(List<String> classes) {
        return "names: [" + String.join(", ", classes) + "]\n"
                + "nc: " + classes.size() + "\n"
                + "train: images\n"
                + "val: images\n";
    }

    private static String labelName(String imageName) {
        int dot = imageName.lastIndexOf('.');
        String stem = dot < 0 ? imageName : imageName.substring(0, dot);
        return stem + ".txt";
    }
}

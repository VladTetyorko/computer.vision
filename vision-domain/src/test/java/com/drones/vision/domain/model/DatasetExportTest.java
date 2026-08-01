package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatasetExportTest {

    private static DatasetExport export(List<String> classes) {
        return new DatasetExport(DatasetId.random(), "export-1", Instant.now(), classes, 10, 12345L,
                "/exports/export-1.zip");
    }

    @Test
    void rejectsNullDatasetId() {
        assertThrows(IllegalArgumentException.class,
                () -> new DatasetExport(null, "export-1", Instant.now(), List.of(), 0, 0L, "/loc"));
    }

    @Test
    void rejectsBlankExportId() {
        assertThrows(IllegalArgumentException.class,
                () -> new DatasetExport(DatasetId.random(), "", Instant.now(), List.of(), 0, 0L, "/loc"));
        assertThrows(IllegalArgumentException.class,
                () -> new DatasetExport(DatasetId.random(), null, Instant.now(), List.of(), 0, 0L, "/loc"));
        assertThrows(IllegalArgumentException.class,
                () -> new DatasetExport(DatasetId.random(), "   ", Instant.now(), List.of(), 0, 0L, "/loc"));
    }

    @Test
    void rejectsNullExportedAt() {
        assertThrows(IllegalArgumentException.class,
                () -> new DatasetExport(DatasetId.random(), "export-1", null, List.of(), 0, 0L, "/loc"));
    }

    @Test
    void rejectsNullClasses() {
        assertThrows(IllegalArgumentException.class,
                () -> new DatasetExport(DatasetId.random(), "export-1", Instant.now(), null, 0, 0L, "/loc"));
    }

    @Test
    void rejectsNegativeSampleCount() {
        assertThrows(IllegalArgumentException.class,
                () -> new DatasetExport(DatasetId.random(), "export-1", Instant.now(), List.of(), -1, 0L, "/loc"));
    }

    @Test
    void rejectsNegativeSizeBytes() {
        assertThrows(IllegalArgumentException.class,
                () -> new DatasetExport(DatasetId.random(), "export-1", Instant.now(), List.of(), 0, -1L, "/loc"));
    }

    @Test
    void rejectsBlankLocation() {
        assertThrows(IllegalArgumentException.class,
                () -> new DatasetExport(DatasetId.random(), "export-1", Instant.now(), List.of(), 0, 0L, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new DatasetExport(DatasetId.random(), "export-1", Instant.now(), List.of(), 0, 0L, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DatasetExport(DatasetId.random(), "export-1", Instant.now(), List.of(), 0, 0L, "   "));
    }

    @Test
    void classesListIsDefensivelyCopied() {
        List<String> classes = new ArrayList<>(List.of("building", "tower"));

        DatasetExport export = export(classes);
        classes.add("mutation");

        assertEquals(List.of("building", "tower"), export.classes(),
                "later mutation of the source list must not affect the export");
        assertThrows(UnsupportedOperationException.class, () -> export.classes().add("nope"),
                "returned classes list must be immutable");
    }
}

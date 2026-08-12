package com.drones.vision.learning.domain.port;

import com.drones.vision.learning.domain.port.DatasetUploadPort.ExportEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link DatasetUploadPort} itself is a plain interface with no behavior of its own to unit-test
 * (same convention as every other port here); its nested {@link ExportEntry} record does carry
 * compact-constructor validation, so that gets the record test treatment — carried over verbatim
 * from the deleted {@code DatasetExportPort}'s own {@code ExportEntry} test.
 */
class DatasetUploadPortTest {

    @Test
    void rejectsBlankImageName() {
        assertThrows(IllegalArgumentException.class, () -> new ExportEntry("", new byte[]{1}, ""));
        assertThrows(IllegalArgumentException.class, () -> new ExportEntry(null, new byte[]{1}, ""));
        assertThrows(IllegalArgumentException.class, () -> new ExportEntry("   ", new byte[]{1}, ""));
    }

    @Test
    void rejectsEmptyImageBytes() {
        assertThrows(IllegalArgumentException.class, () -> new ExportEntry("sample.jpg", null, ""));
        assertThrows(IllegalArgumentException.class, () -> new ExportEntry("sample.jpg", new byte[0], ""));
    }

    @Test
    void rejectsNullLabelFileText() {
        assertThrows(IllegalArgumentException.class, () -> new ExportEntry("sample.jpg", new byte[]{1}, null));
    }

    @Test
    void allowsEmptyLabelFileTextForABackgroundSample() {
        ExportEntry entry = new ExportEntry("sample.jpg", new byte[]{1}, "");

        assertEquals("", entry.labelFileText());
    }

    @Test
    void acceptsAWellFormedEntry() {
        ExportEntry entry = new ExportEntry("sample.jpg", new byte[]{1, 2, 3}, "0 0.5 0.5 0.2 0.2\n");

        assertEquals("sample.jpg", entry.imageName());
        assertEquals("0 0.5 0.5 0.2 0.2\n", entry.labelFileText());
    }
}

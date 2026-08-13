package com.drones.vision.learning.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatasetUploadTest {

    @Test
    void rejectsNullDatasetId() {
        assertThrows(IllegalArgumentException.class,
                () -> new DatasetUpload(null, Instant.now(), 0, 0L));
    }

    @Test
    void rejectsNullUploadedAt() {
        assertThrows(IllegalArgumentException.class,
                () -> new DatasetUpload(DatasetId.random(), null, 0, 0L));
    }

    @Test
    void rejectsNegativeSampleCount() {
        assertThrows(IllegalArgumentException.class,
                () -> new DatasetUpload(DatasetId.random(), Instant.now(), -1, 0L));
    }

    @Test
    void rejectsNegativeSizeBytes() {
        assertThrows(IllegalArgumentException.class,
                () -> new DatasetUpload(DatasetId.random(), Instant.now(), 0, -1L));
    }

    @Test
    void acceptsAZeroSampleCountAndZeroSizeBytes() {
        DatasetUpload upload = new DatasetUpload(DatasetId.random(), Instant.now(), 0, 0L);

        assertEquals(0, upload.sampleCount());
        assertEquals(0L, upload.sizeBytes());
    }

    @Test
    void acceptsAWellFormedUpload() {
        DatasetId datasetId = DatasetId.random();
        Instant uploadedAt = Instant.now();

        DatasetUpload upload = new DatasetUpload(datasetId, uploadedAt, 42, 123456L);

        assertEquals(datasetId, upload.datasetId());
        assertEquals(uploadedAt, upload.uploadedAt());
        assertEquals(42, upload.sampleCount());
        assertEquals(123456L, upload.sizeBytes());
    }
}

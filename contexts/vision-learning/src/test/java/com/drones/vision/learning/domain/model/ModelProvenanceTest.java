package com.drones.vision.learning.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelProvenanceTest {

    @Test
    void noneHasEveryFieldNull() {
        ModelProvenance none = ModelProvenance.none();

        assertNull(none.datasetId());
        assertNull(none.trainingRunId());
        assertNull(none.baseModel());
        assertNull(none.epochs());
        assertNull(none.trainedAt());
    }

    @Test
    void allowsEveryFieldNull() {
        ModelProvenance provenance = new ModelProvenance(null, null, null, null, null);

        assertEquals(ModelProvenance.none(), provenance);
    }

    @Test
    void rejectsBlankBaseModelWhenPresent() {
        assertThrows(IllegalArgumentException.class,
                () -> new ModelProvenance(null, null, "", 50, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelProvenance(null, null, "   ", 50, null));
    }

    @Test
    void rejectsNonPositiveEpochsWhenPresent() {
        assertThrows(IllegalArgumentException.class,
                () -> new ModelProvenance(null, null, "yolo26n.pt", 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelProvenance(null, null, "yolo26n.pt", -1, null));
    }

    @Test
    void acceptsAFullyPopulatedProvenance() {
        DatasetId datasetId = DatasetId.random();
        TrainingRunId runId = TrainingRunId.random();
        Instant trainedAt = Instant.now();

        ModelProvenance provenance = new ModelProvenance(datasetId, runId, "yolo26n.pt", 50, trainedAt);

        assertEquals(datasetId, provenance.datasetId());
        assertEquals(runId, provenance.trainingRunId());
        assertEquals("yolo26n.pt", provenance.baseModel());
        assertEquals(50, provenance.epochs());
        assertEquals(trainedAt, provenance.trainedAt());
    }

    @Test
    void allowsPartiallyPopulatedProvenance() {
        ModelProvenance provenance = new ModelProvenance(null, null, "yolo26n.pt", 50, null);

        assertNull(provenance.datasetId());
        assertNull(provenance.trainingRunId());
        assertEquals("yolo26n.pt", provenance.baseModel());
        assertEquals(50, provenance.epochs());
        assertNull(provenance.trainedAt());
    }
}

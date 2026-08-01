package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrainingJobSpecTest {

    @Test
    void rejectsBlankBaseModel() {
        assertThrows(IllegalArgumentException.class, () -> new TrainingJobSpec("", "dataset-1", 10));
        assertThrows(IllegalArgumentException.class, () -> new TrainingJobSpec(null, "dataset-1", 10));
        assertThrows(IllegalArgumentException.class, () -> new TrainingJobSpec("   ", "dataset-1", 10));
    }

    @Test
    void rejectsBlankDatasetId() {
        assertThrows(IllegalArgumentException.class, () -> new TrainingJobSpec("yolo26n.pt", "", 10));
        assertThrows(IllegalArgumentException.class, () -> new TrainingJobSpec("yolo26n.pt", null, 10));
        assertThrows(IllegalArgumentException.class, () -> new TrainingJobSpec("yolo26n.pt", "   ", 10));
    }

    @Test
    void rejectsNonPositiveEpochs() {
        assertThrows(IllegalArgumentException.class, () -> new TrainingJobSpec("yolo26n.pt", "dataset-1", 0));
        assertThrows(IllegalArgumentException.class, () -> new TrainingJobSpec("yolo26n.pt", "dataset-1", -1));
    }

    @Test
    void acceptsAWellFormedSpec() {
        TrainingJobSpec spec = new TrainingJobSpec("yolo26n.pt", "dataset-1", 50);

        assertEquals("yolo26n.pt", spec.baseModel());
        assertEquals("dataset-1", spec.datasetId());
        assertEquals(50, spec.epochs());
    }
}

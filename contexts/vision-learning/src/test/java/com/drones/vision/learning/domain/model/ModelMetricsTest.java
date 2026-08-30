package com.drones.vision.learning.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelMetricsTest {

    @Test
    void rejectsNullKind() {
        assertThrows(IllegalArgumentException.class, () -> new ModelMetrics(0.71, null));
    }

    @Test
    void allowsNullMap50() {
        ModelMetrics metrics = new ModelMetrics(null, MetricsKind.TRAINING);

        assertNull(metrics.map50());
        assertEquals(MetricsKind.TRAINING, metrics.kind());
    }

    @Test
    void acceptsAWellFormedTrainingMetric() {
        ModelMetrics metrics = new ModelMetrics(0.71, MetricsKind.TRAINING);

        assertEquals(0.71, metrics.map50());
        assertEquals(MetricsKind.TRAINING, metrics.kind());
    }

    @Test
    void acceptsAWorkerReportedMetric() {
        ModelMetrics metrics = new ModelMetrics(0.5, MetricsKind.WORKER);

        assertEquals(MetricsKind.WORKER, metrics.kind());
    }
}

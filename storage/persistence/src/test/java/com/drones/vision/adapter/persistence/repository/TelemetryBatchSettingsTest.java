package com.drones.vision.adapter.persistence.repository;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compact-constructor validation and the {@link TelemetryBatchSettings#defaults()}/{@link
 * TelemetryBatchSettings#immediate()} factories -- docker-free, mirroring {@code
 * PersistencePoolSettingsTest}'s own shape for {@code config.PersistencePoolSettings}.
 */
class TelemetryBatchSettingsTest {

    @Test
    void defaultsMatchTheDocumentedConstants() {
        TelemetryBatchSettings settings = TelemetryBatchSettings.defaults();

        assertEquals(TelemetryBatchSettings.DEFAULT_BATCH_SIZE_SAMPLES, settings.batchSizeSamples());
        assertEquals(TelemetryBatchSettings.DEFAULT_BATCH_WINDOW_MILLIS, settings.batchWindowMillis());
        assertFalse(settings.isImmediate(), "a non-zero default window means defaults() is not immediate mode");
    }

    @Test
    void immediateIsAOneSampleZeroWindowBatch() {
        TelemetryBatchSettings settings = TelemetryBatchSettings.immediate();

        assertEquals(1, settings.batchSizeSamples());
        assertEquals(0L, settings.batchWindowMillis());
        assertTrue(settings.isImmediate());
    }

    @Test
    void rejectsABatchSizeBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> new TelemetryBatchSettings(0, 200));
    }

    @Test
    void rejectsANegativeBatchWindow() {
        assertThrows(IllegalArgumentException.class, () -> new TelemetryBatchSettings(10, -1));
    }

    @Test
    void zeroWindowIsAllowedAndReadsAsImmediate() {
        TelemetryBatchSettings settings = new TelemetryBatchSettings(10, 0);

        assertTrue(settings.isImmediate());
    }

    @Test
    void aPositiveWindowIsNotImmediateRegardlessOfBatchSize() {
        TelemetryBatchSettings settings = new TelemetryBatchSettings(1, 50);

        assertFalse(settings.isImmediate());
    }
}

package com.drones.vision.adapter.persistence.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Compact-constructor validation and the {@link PersistencePoolSettings#defaults()} factory. */
class PersistencePoolSettingsTest {

    @Test
    void defaultsMatchTheDocumentedConstants() {
        PersistencePoolSettings settings = PersistencePoolSettings.defaults();

        assertEquals(PersistencePoolSettings.DEFAULT_MAXIMUM_POOL_SIZE, settings.maximumPoolSize());
        assertEquals(PersistencePoolSettings.DEFAULT_MINIMUM_IDLE, settings.minimumIdle());
        assertEquals(PersistencePoolSettings.DEFAULT_CONNECTION_TIMEOUT_MILLIS, settings.connectionTimeoutMillis());
        assertEquals(PersistencePoolSettings.DEFAULT_LEAK_DETECTION_THRESHOLD_MILLIS,
                settings.leakDetectionThresholdMillis());
    }

    @Test
    void rejectsAMaximumPoolSizeBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> new PersistencePoolSettings(0, 0, 1000, 0));
    }

    @Test
    void rejectsAMinimumIdleAboveMaximumPoolSize() {
        assertThrows(IllegalArgumentException.class, () -> new PersistencePoolSettings(5, 6, 1000, 0));
    }

    @Test
    void rejectsANegativeMinimumIdle() {
        assertThrows(IllegalArgumentException.class, () -> new PersistencePoolSettings(5, -1, 1000, 0));
    }

    @Test
    void rejectsANonPositiveConnectionTimeout() {
        assertThrows(IllegalArgumentException.class, () -> new PersistencePoolSettings(5, 1, 0, 0));
    }

    @Test
    void rejectsANegativeLeakDetectionThreshold() {
        assertThrows(IllegalArgumentException.class, () -> new PersistencePoolSettings(5, 1, 1000, -1));
    }

    @Test
    void zeroLeakDetectionThresholdIsAllowedAsDisabled() {
        PersistencePoolSettings settings = new PersistencePoolSettings(5, 1, 1000, 0);

        assertEquals(0L, settings.leakDetectionThresholdMillis());
    }
}

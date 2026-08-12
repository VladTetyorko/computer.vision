package com.drones.vision.perception.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrackingTelemetryTest {

    @Test
    void rejectsMissingReasonWhenDetectorRan() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrackingTelemetry(true, null, Duration.ZERO, "lk", 0L));
    }

    @Test
    void allowsMissingReasonWhenDetectorDidNotRun() {
        assertDoesNotThrow(() -> new TrackingTelemetry(false, null, Duration.ofMillis(0), "lk", 7L));
    }

    @Test
    void rejectsNullTrackerLatency() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrackingTelemetry(true, DetectorReason.CADENCE, null, "lk", 0L));
    }

    @Test
    void rejectsNegativeTrackerLatency() {
        assertThrows(IllegalArgumentException.class, () -> new TrackingTelemetry(
                true, DetectorReason.CADENCE, Duration.ofMillis(-1), "lk", 0L));
    }

    @Test
    void rejectsNullEngineId() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrackingTelemetry(true, DetectorReason.ALWAYS, Duration.ZERO, null, 0L));
    }

    @Test
    void rejectsNegativeLockedTrackId() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrackingTelemetry(false, null, Duration.ZERO, "", -1L));
    }

    @Test
    void acceptsWellFormedTelemetry() {
        assertDoesNotThrow(() -> new TrackingTelemetry(
                true, DetectorReason.TRACKER_FAILED, Duration.ofMillis(1), "lk", 7L));
    }
}

package com.drones.vision.perception.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void fiveArgConvenienceConstructorDefaultsV3FieldsToAbsent() {
        TrackingTelemetry telemetry = new TrackingTelemetry(false, null, Duration.ZERO, "lk", 0L);

        assertEquals(Duration.ZERO, telemetry.detectionLag());
        assertEquals(Duration.ZERO, telemetry.reupdateLatency());
        assertEquals(0, telemetry.reupdatedTracks());
        assertNull(telemetry.capability(), "no level reported at all -- a pre-V3 cv-service");
    }

    @Test
    void rejectsNullDetectionLagOrReupdateLatency() {
        assertThrows(IllegalArgumentException.class, () -> new TrackingTelemetry(
                false, null, Duration.ZERO, "lk", 0L, null, Duration.ZERO, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new TrackingTelemetry(
                false, null, Duration.ZERO, "lk", 0L, Duration.ZERO, null, 0, null));
    }

    @Test
    void rejectsNegativeDetectionLag() {
        assertThrows(IllegalArgumentException.class, () -> new TrackingTelemetry(
                false, null, Duration.ZERO, "lk", 0L, Duration.ofMillis(-1), Duration.ZERO, 0, null));
    }

    @Test
    void rejectsNegativeReupdateLatency() {
        assertThrows(IllegalArgumentException.class, () -> new TrackingTelemetry(
                false, null, Duration.ZERO, "lk", 0L, Duration.ZERO, Duration.ofMillis(-1), 0, null));
    }

    @Test
    void rejectsNegativeReupdatedTracks() {
        assertThrows(IllegalArgumentException.class, () -> new TrackingTelemetry(
                false, null, Duration.ZERO, "lk", 0L, Duration.ZERO, Duration.ZERO, -1, null));
    }

    @Test
    void acceptsWellFormedV3Telemetry() {
        TrackingCapability capability = new TrackingCapability(2, "host affords L2 only");

        TrackingTelemetry telemetry = new TrackingTelemetry(true, DetectorReason.CADENCE, Duration.ofMillis(5), "lk",
                7L, Duration.ofMillis(40), Duration.ofMillis(12), 3, capability);

        assertEquals(Duration.ofMillis(40), telemetry.detectionLag());
        assertEquals(Duration.ofMillis(12), telemetry.reupdateLatency());
        assertEquals(3, telemetry.reupdatedTracks());
        assertEquals(capability, telemetry.capability());
    }
}

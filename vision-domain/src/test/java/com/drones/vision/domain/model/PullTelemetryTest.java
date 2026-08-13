package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PullTelemetryTest {

    @Test
    void rejectsNegativeDecodeMillis() {
        assertThrows(IllegalArgumentException.class, () -> new PullTelemetry(-1L, 10f, 10f, 0L, 0L, 0L));
    }

    @Test
    void rejectsNegativeSourceFps() {
        assertThrows(IllegalArgumentException.class, () -> new PullTelemetry(0L, -1f, 10f, 0L, 0L, 0L));
    }

    @Test
    void rejectsNegativeAchievedFps() {
        assertThrows(IllegalArgumentException.class, () -> new PullTelemetry(0L, 10f, -1f, 0L, 0L, 0L));
    }

    @Test
    void rejectsNegativeDroppedFrames() {
        assertThrows(IllegalArgumentException.class, () -> new PullTelemetry(0L, 10f, 10f, -1L, 0L, 0L));
    }

    @Test
    void rejectsNegativeMissedDeadlines() {
        assertThrows(IllegalArgumentException.class, () -> new PullTelemetry(0L, 10f, 10f, 0L, -1L, 0L));
    }

    @Test
    void allowsNegativeCaptureSkewMillis() {
        // an estimate of (local receipt - capture), not a measured duration -- a worker clock
        // running behind the camera's own is exactly the case this field exists to surface.
        assertDoesNotThrow(() -> new PullTelemetry(0L, 10f, 10f, 0L, 0L, -5L));
    }

    @Test
    void acceptsWellFormedTelemetry() {
        assertDoesNotThrow(() -> new PullTelemetry(3L, 9.9f, 9.5f, 2L, 1L, 12L));
    }
}

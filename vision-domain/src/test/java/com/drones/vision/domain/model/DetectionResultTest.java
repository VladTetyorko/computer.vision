package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DetectionResultTest {

    private static Detection detection() {
        return new Detection("person", 0.9, new BoundingBox(0, 0, 0.1, 0.1), new ModelRef("yolo", "1"));
    }

    @Test
    void detectionsListIsDefensivelyCopied() {
        List<Detection> detections = new ArrayList<>();
        detections.add(detection());

        DetectionResult result = new DetectionResult(
                StreamId.random(), 0L, Instant.now(), detections, Duration.ofMillis(20));

        detections.add(detection());

        assertEquals(1, result.detections().size(), "later mutation of the source list must not affect the result");
        assertThrows(UnsupportedOperationException.class, () -> result.detections().add(detection()),
                "returned detections list must be immutable");
    }

    @Test
    void rejectsInvalidArguments() {
        StreamId streamId = StreamId.random();
        Instant now = Instant.now();
        List<Detection> detections = List.of(detection());
        Duration latency = Duration.ofMillis(10);

        assertThrows(IllegalArgumentException.class,
                () -> new DetectionResult(null, 0L, now, detections, latency));
        assertThrows(IllegalArgumentException.class,
                () -> new DetectionResult(streamId, -1L, now, detections, latency));
        assertThrows(IllegalArgumentException.class,
                () -> new DetectionResult(streamId, 0L, null, detections, latency));
        assertThrows(IllegalArgumentException.class,
                () -> new DetectionResult(streamId, 0L, now, null, latency));
        assertThrows(IllegalArgumentException.class,
                () -> new DetectionResult(streamId, 0L, now, detections, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DetectionResult(streamId, 0L, now, detections, Duration.ofMillis(-1)));
    }

    @Test
    void allowsEmptyDetections() {
        DetectionResult result = new DetectionResult(
                StreamId.random(), 0L, Instant.now(), List.of(), Duration.ZERO);

        assertEquals(0, result.detections().size());
    }

    @Test
    void fiveArgConstructorEqualsSixArgConstructorWithNullTracking() {
        StreamId streamId = StreamId.random();
        Instant now = Instant.now();
        List<Detection> detections = List.of(detection());
        Duration latency = Duration.ofMillis(10);

        DetectionResult viaConvenience = new DetectionResult(streamId, 0L, now, detections, latency);
        DetectionResult viaCanonical = new DetectionResult(streamId, 0L, now, detections, latency, null);

        assertEquals(viaCanonical, viaConvenience);
        assertNull(viaConvenience.tracking());
    }

    @Test
    void canonicalConstructorAcceptsExplicitTracking() {
        TrackingTelemetry tracking =
                new TrackingTelemetry(false, null, Duration.ofMillis(0), "lk", 7L);

        DetectionResult result = new DetectionResult(
                StreamId.random(), 0L, Instant.now(), List.of(detection()), Duration.ofMillis(10), tracking);

        assertEquals(tracking, result.tracking());
    }

    @Test
    void sixArgConstructorEqualsSevenArgConstructorWithNullPullTelemetry() {
        StreamId streamId = StreamId.random();
        Instant now = Instant.now();
        List<Detection> detections = List.of(detection());
        Duration latency = Duration.ofMillis(10);
        TrackingTelemetry tracking = new TrackingTelemetry(false, null, Duration.ZERO, "lk", 0L);

        DetectionResult viaConvenience = new DetectionResult(streamId, 0L, now, detections, latency, tracking);
        DetectionResult viaCanonical = new DetectionResult(streamId, 0L, now, detections, latency, tracking, null);

        assertEquals(viaCanonical, viaConvenience);
        assertNull(viaConvenience.pullTelemetry());
    }

    @Test
    void canonicalConstructorAcceptsExplicitPullTelemetry() {
        PullTelemetry pullTelemetry = new PullTelemetry(3L, 9.9f, 9.5f, 2L, 1L, 12L);

        DetectionResult result = new DetectionResult(StreamId.random(), 0L, Instant.now(), List.of(detection()),
                Duration.ofMillis(10), null, pullTelemetry);

        assertEquals(pullTelemetry, result.pullTelemetry());
        assertNull(result.tracking());
    }
}

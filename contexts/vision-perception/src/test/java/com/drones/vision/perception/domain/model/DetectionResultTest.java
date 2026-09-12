package com.drones.vision.perception.domain.model;

import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.kernel.StreamId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectionResultTest {

    private static Detection detection() {
        return new Detection("person", 0.9, new BoundingBox(0, 0, 0.1, 0.1), new ModelRef("yolo", "1"));
    }

    @Test
    void detectionsListIsDefensivelyCopied() {
        List<Detection> detections = new ArrayList<>();
        detections.add(detection());

        DetectionResult result = new DetectionResult(
                StreamId.random(), 0L, Instant.now(), detections, Duration.ofMillis(20), null, null, List.of(), Optional.empty());

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
                () -> new DetectionResult(null, 0L, now, detections, latency, null, null, List.of(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new DetectionResult(streamId, -1L, now, detections, latency, null, null, List.of(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new DetectionResult(streamId, 0L, null, detections, latency, null, null, List.of(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new DetectionResult(streamId, 0L, now, null, latency, null, null, List.of(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new DetectionResult(streamId, 0L, now, detections, null, null, null, List.of(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new DetectionResult(
                        streamId, 0L, now, detections, Duration.ofMillis(-1), null, null, List.of(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new DetectionResult(streamId, 0L, now, detections, latency, null, null, null, Optional.empty()),
                "objects must never be null — an empty list is the honest 'no mirror' value, not null");
    }

    @Test
    void allowsEmptyDetections() {
        DetectionResult result = new DetectionResult(
                StreamId.random(), 0L, Instant.now(), List.of(), Duration.ZERO, null, null, List.of(), Optional.empty());

        assertEquals(0, result.detections().size());
    }

    @Test
    void canonicalConstructorAcceptsExplicitTracking() {
        TrackingTelemetry tracking =
                new TrackingTelemetry(false, null, Duration.ofMillis(0), "lk", 7L);

        DetectionResult result = new DetectionResult(StreamId.random(), 0L, Instant.now(), List.of(detection()),
                Duration.ofMillis(10), tracking, null, List.of(), Optional.empty());

        assertEquals(tracking, result.tracking());
        assertNull(result.pullTelemetry());
    }

    @Test
    void canonicalConstructorAcceptsExplicitPullTelemetry() {
        PullTelemetry pullTelemetry = new PullTelemetry(3L, 9.9f, 9.5f, 2L, 1L, 12L);

        DetectionResult result = new DetectionResult(StreamId.random(), 0L, Instant.now(), List.of(detection()),
                Duration.ofMillis(10), null, pullTelemetry, List.of(), Optional.empty());

        assertEquals(pullTelemetry, result.pullTelemetry());
        assertNull(result.tracking());
    }

    @Test
    void objectsDefaultsToEmptyForAResultWithNoMirror() {
        DetectionResult result = new DetectionResult(StreamId.random(), 0L, Instant.now(), List.of(detection()),
                Duration.ofMillis(10), null, null, List.of(), Optional.empty());

        assertTrue(result.objects().isEmpty(), "no mirror produced => empty list, never null");
    }

    @Test
    void objectsListIsDefensivelyCopied() {
        List<ObjectState> objects = new ArrayList<>();
        objects.add(ObjectStateFixtures.everyFieldDistinct());

        DetectionResult result = new DetectionResult(StreamId.random(), 0L, Instant.now(), List.of(),
                Duration.ZERO, null, null, objects, Optional.empty());

        objects.add(ObjectStateFixtures.everyFieldDistinct());

        assertEquals(1, result.objects().size(), "later mutation of the source list must not affect the result");
        assertThrows(UnsupportedOperationException.class,
                () -> result.objects().add(ObjectStateFixtures.everyFieldDistinct()),
                "returned objects list must be immutable");
    }

    @Test
    void objectsCanCarryAMirroredObjectDistinctFromDetections() {
        ObjectState coastingWithNoDetection = ObjectStateFixtures.everyFieldDistinct();

        DetectionResult result = new DetectionResult(StreamId.random(), 0L, Instant.now(), List.of(),
                Duration.ZERO, null, null, List.of(coastingWithNoDetection), Optional.empty());

        assertTrue(result.detections().isEmpty(), "no detection this frame");
        assertEquals(1, result.objects().size(), "the coasting/dormant object still appears in objects");
    }

    // -- ledger (CV-ORCHESTRATION wave W2) --------------------------------

    @Test
    void rejectsNullLedger() {
        StreamId streamId = StreamId.random();
        Instant now = Instant.now();

        assertThrows(IllegalArgumentException.class,
                () -> new DetectionResult(streamId, 0L, now, List.of(), Duration.ZERO, null, null, List.of(), null),
                "ledger must never be null — Optional.empty() is the honest 'not traced' value, not null");
    }

    @Test
    void ledgerDefaultsToEmptyForAnUntracedFrame() {
        DetectionResult result = new DetectionResult(StreamId.random(), 0L, Instant.now(), List.of(), Duration.ZERO,
                null, null, List.of(), Optional.empty());

        assertEquals(Optional.empty(), result.ledger());
    }

    @Test
    void aPresentLedgerRoundTrips() {
        FrameLedger ledger = FrameLedgerFixtures.everyFieldDistinct();

        DetectionResult result = new DetectionResult(StreamId.random(), 0L, Instant.now(), List.of(), Duration.ZERO,
                null, null, List.of(), Optional.of(ledger));

        assertEquals(Optional.of(ledger), result.ledger());
    }
}

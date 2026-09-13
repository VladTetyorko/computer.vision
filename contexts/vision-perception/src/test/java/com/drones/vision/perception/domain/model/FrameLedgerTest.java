package com.drones.vision.perception.domain.model;

import com.drones.vision.kernel.StreamId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FrameLedgerTest {

    private static final StreamId STREAM_ID = StreamId.random();
    private static final Instant CAPTURED_AT = Instant.now();

    private static FrameLedger ledger(StreamId streamId, long sequence, Instant capturedAt, int levelServed,
                                       String detectorReason, List<String> eligible, List<LedgerEntry> entries,
                                       Map<Long, List<ObjectEvidence>> objects, int dropsSinceLast,
                                       double gateWaitMillis, double totalMillis, boolean halted,
                                       List<DetectorBox> detections, int frameWidth, int frameHeight) {
        return new FrameLedger(streamId, sequence, capturedAt, levelServed, detectorReason, eligible, entries,
                objects, dropsSinceLast, gateWaitMillis, totalMillis, halted, detections, frameWidth, frameHeight);
    }

    private static FrameLedger wellFormed() {
        return ledger(STREAM_ID, 0L, CAPTURED_AT, 1, "ALWAYS", List.of("detect"), List.of(), Map.of(), 0, 0.0, 0.0,
                false, List.of(), 0, 0);
    }

    @Test
    void rejectsNullStreamId() {
        assertThrows(IllegalArgumentException.class,
                () -> ledger(null, 0L, CAPTURED_AT, 1, "ALWAYS", List.of(), List.of(), Map.of(), 0, 0.0, 0.0, false, List.of(), 0, 0));
    }

    @Test
    void rejectsNegativeSequence() {
        assertThrows(IllegalArgumentException.class,
                () -> ledger(STREAM_ID, -1L, CAPTURED_AT, 1, "ALWAYS", List.of(), List.of(), Map.of(), 0, 0.0, 0.0,
                        false, List.of(), 0, 0));
    }

    @Test
    void rejectsNullCapturedAt() {
        assertThrows(IllegalArgumentException.class,
                () -> ledger(STREAM_ID, 0L, null, 1, "ALWAYS", List.of(), List.of(), Map.of(), 0, 0.0, 0.0, false, List.of(), 0, 0));
    }

    @Test
    void rejectsNegativeLevelServed() {
        assertThrows(IllegalArgumentException.class,
                () -> ledger(STREAM_ID, 0L, CAPTURED_AT, -1, "ALWAYS", List.of(), List.of(), Map.of(), 0, 0.0, 0.0,
                        false, List.of(), 0, 0));
    }

    @Test
    void rejectsNullDetectorReason() {
        assertThrows(IllegalArgumentException.class,
                () -> ledger(STREAM_ID, 0L, CAPTURED_AT, 1, null, List.of(), List.of(), Map.of(), 0, 0.0, 0.0,
                        false, List.of(), 0, 0));
    }

    @Test
    void allowsEmptyDetectorReason() {
        FrameLedger ledger = ledger(STREAM_ID, 0L, CAPTURED_AT, 1, "", List.of(), List.of(), Map.of(), 0, 0.0, 0.0,
                false, List.of(), 0, 0);

        assertEquals("", ledger.detectorReason());
    }

    @Test
    void rejectsNullEligible() {
        assertThrows(IllegalArgumentException.class,
                () -> ledger(STREAM_ID, 0L, CAPTURED_AT, 1, "ALWAYS", null, List.of(), Map.of(), 0, 0.0, 0.0, false, List.of(), 0, 0));
    }

    @Test
    void rejectsNullEntries() {
        assertThrows(IllegalArgumentException.class,
                () -> ledger(STREAM_ID, 0L, CAPTURED_AT, 1, "ALWAYS", List.of(), null, Map.of(), 0, 0.0, 0.0, false, List.of(), 0, 0));
    }

    @Test
    void rejectsNullObjects() {
        assertThrows(IllegalArgumentException.class,
                () -> ledger(STREAM_ID, 0L, CAPTURED_AT, 1, "ALWAYS", List.of(), List.of(), null, 0, 0.0, 0.0,
                        false, List.of(), 0, 0));
    }

    @Test
    void rejectsNegativeDropsSinceLast() {
        assertThrows(IllegalArgumentException.class,
                () -> ledger(STREAM_ID, 0L, CAPTURED_AT, 1, "ALWAYS", List.of(), List.of(), Map.of(), -1, 0.0, 0.0,
                        false, List.of(), 0, 0));
    }

    @Test
    void rejectsNonFiniteOrNegativeGateWaitMillis() {
        assertThrows(IllegalArgumentException.class,
                () -> ledger(STREAM_ID, 0L, CAPTURED_AT, 1, "ALWAYS", List.of(), List.of(), Map.of(), 0, Double.NaN,
                        0.0, false, List.of(), 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ledger(STREAM_ID, 0L, CAPTURED_AT, 1, "ALWAYS", List.of(), List.of(), Map.of(), 0, -1.0, 0.0,
                        false, List.of(), 0, 0));
    }

    @Test
    void rejectsNonFiniteOrNegativeTotalMillis() {
        assertThrows(IllegalArgumentException.class,
                () -> ledger(STREAM_ID, 0L, CAPTURED_AT, 1, "ALWAYS", List.of(), List.of(), Map.of(), 0, 0.0,
                        Double.POSITIVE_INFINITY, false, List.of(), 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ledger(STREAM_ID, 0L, CAPTURED_AT, 1, "ALWAYS", List.of(), List.of(), Map.of(), 0, 0.0, -1.0,
                        false, List.of(), 0, 0));
    }

    @Test
    void acceptsWellFormedLedger() {
        FrameLedger ledger = wellFormed();

        assertEquals(STREAM_ID, ledger.streamId());
    }

    @Test
    void eligibleIsDefensivelyCopiedAndImmutable() {
        List<String> eligible = new ArrayList<>();
        eligible.add("detect");

        FrameLedger ledger = ledger(STREAM_ID, 0L, CAPTURED_AT, 1, "ALWAYS", eligible, List.of(), Map.of(), 0, 0.0,
                0.0, false, List.of(), 0, 0);
        eligible.add("assoc");

        assertEquals(1, ledger.eligible().size(), "later mutation of the source list must not affect the ledger");
        assertThrows(UnsupportedOperationException.class, () -> ledger.eligible().add("follow"),
                "returned eligible list must be immutable");
    }

    @Test
    void entriesIsDefensivelyCopiedAndImmutable() {
        List<LedgerEntry> entries = new ArrayList<>();
        entries.add(new LedgerEntry("detect.full", LedgerOutcome.RAN, "", 1.0, Map.of()));

        FrameLedger ledger = ledger(STREAM_ID, 0L, CAPTURED_AT, 1, "ALWAYS", List.of(), entries, Map.of(), 0, 0.0,
                0.0, false, List.of(), 0, 0);
        entries.add(new LedgerEntry("assoc.cost", LedgerOutcome.SKIPPED, "budget", 2.0, Map.of()));

        assertEquals(1, ledger.entries().size(), "later mutation of the source list must not affect the ledger");
        assertThrows(UnsupportedOperationException.class,
                () -> ledger.entries().add(new LedgerEntry("x", LedgerOutcome.FAILED, "boom", 0.0, Map.of())),
                "returned entries list must be immutable");
    }

    @Test
    void objectsIsDefensivelyCopiedAndImmutableIncludingValueLists() {
        List<ObjectEvidence> evidenceForOne = new ArrayList<>();
        evidenceForOne.add(new ObjectEvidence("assoc.cost", Map.of("iou", "0.5")));
        Map<Long, List<ObjectEvidence>> objects = new HashMap<>();
        objects.put(1L, evidenceForOne);

        FrameLedger ledger = ledger(STREAM_ID, 0L, CAPTURED_AT, 1, "ALWAYS", List.of(), List.of(), objects, 0, 0.0,
                0.0, false, List.of(), 0, 0);

        // mutate both the source map and the source value list after construction
        objects.put(2L, List.of(new ObjectEvidence("memory.gallery", Map.of())));
        evidenceForOne.add(new ObjectEvidence("memory.gallery", Map.of("distance", "0.1")));

        assertEquals(1, ledger.objects().size(), "later mutation of the source map must not affect the ledger");
        assertEquals(1, ledger.objects().get(1L).size(),
                "later mutation of the source value list must not affect the ledger");
        assertThrows(UnsupportedOperationException.class, () -> ledger.objects().put(3L, List.of()),
                "returned objects map must be immutable");
        assertThrows(UnsupportedOperationException.class,
                () -> ledger.objects().get(1L).add(new ObjectEvidence("x", Map.of())),
                "returned per-track evidence list must be immutable");
    }

    // -- CV-ORCHESTRATION wave W5b: detections / frameWidth / frameHeight --------------------------

    @Test
    void rejectsNullDetections() {
        assertThrows(IllegalArgumentException.class,
                () -> ledger(STREAM_ID, 0L, CAPTURED_AT, 1, "ALWAYS", List.of(), List.of(), Map.of(), 0, 0.0, 0.0,
                        false, null, 0, 0));
    }

    @Test
    void rejectsNegativeFrameWidth() {
        assertThrows(IllegalArgumentException.class,
                () -> ledger(STREAM_ID, 0L, CAPTURED_AT, 1, "ALWAYS", List.of(), List.of(), Map.of(), 0, 0.0, 0.0,
                        false, List.of(), -1, 0));
    }

    @Test
    void rejectsNegativeFrameHeight() {
        assertThrows(IllegalArgumentException.class,
                () -> ledger(STREAM_ID, 0L, CAPTURED_AT, 1, "ALWAYS", List.of(), List.of(), Map.of(), 0, 0.0, 0.0,
                        false, List.of(), 0, -1));
    }

    @Test
    void allowsAnEmptyDetectionsListWithZeroFrameSize() {
        // The "not carried" default -- an untraced frame's ledger, or a pre-W5b harness.
        FrameLedger ledger = ledger(STREAM_ID, 0L, CAPTURED_AT, 1, "ALWAYS", List.of(), List.of(), Map.of(), 0, 0.0,
                0.0, false, List.of(), 0, 0);

        assertEquals(List.of(), ledger.detections());
        assertEquals(0, ledger.frameWidth());
        assertEquals(0, ledger.frameHeight());
    }

    @Test
    void detectionsIsDefensivelyCopiedAndImmutable() {
        List<DetectorBox> detections = new ArrayList<>();
        detections.add(new DetectorBox("car", 0.9, new com.drones.vision.kernel.BoundingBox(0.1, 0.1, 0.2, 0.2)));

        FrameLedger ledger = ledger(STREAM_ID, 0L, CAPTURED_AT, 1, "ALWAYS", List.of(), List.of(), Map.of(), 0, 0.0,
                0.0, false, detections, 640, 480);
        detections.add(new DetectorBox("person", 0.5, new com.drones.vision.kernel.BoundingBox(0.3, 0.3, 0.1, 0.1)));

        assertEquals(1, ledger.detections().size(), "later mutation of the source list must not affect the ledger");
        assertThrows(UnsupportedOperationException.class,
                () -> ledger.detections().add(new DetectorBox("x", 0.1,
                        new com.drones.vision.kernel.BoundingBox(0.0, 0.0, 0.1, 0.1))),
                "returned detections list must be immutable");
        assertEquals(640, ledger.frameWidth());
        assertEquals(480, ledger.frameHeight());
    }
}

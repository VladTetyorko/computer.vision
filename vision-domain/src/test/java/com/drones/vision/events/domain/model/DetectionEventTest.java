package com.drones.vision.events.domain.model;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.StreamId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DetectionEventTest {

    private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");
    private final StreamId streamId = StreamId.random();
    private final AssetId assetId = AssetId.random();

    private DetectionEvent event(Instant firstSeen, Instant lastSeen) {
        return new DetectionEvent(DetectionEventId.random(), streamId, assetId, "person", 0.6, firstSeen, lastSeen,
                DetectionEventState.OPEN, null);
    }

    @Test
    void rejectsInvalidArguments() {
        DetectionEventId id = DetectionEventId.random();

        assertThrows(IllegalArgumentException.class,
                () -> new DetectionEvent(null, streamId, assetId, "person", 0.5, T0, T0, DetectionEventState.OPEN,
                        null));
        assertThrows(IllegalArgumentException.class,
                () -> new DetectionEvent(id, null, assetId, "person", 0.5, T0, T0, DetectionEventState.OPEN, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DetectionEvent(id, streamId, assetId, null, 0.5, T0, T0, DetectionEventState.OPEN, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DetectionEvent(id, streamId, assetId, "  ", 0.5, T0, T0, DetectionEventState.OPEN, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DetectionEvent(id, streamId, assetId, "person", -0.01, T0, T0, DetectionEventState.OPEN,
                        null));
        assertThrows(IllegalArgumentException.class,
                () -> new DetectionEvent(id, streamId, assetId, "person", 1.01, T0, T0, DetectionEventState.OPEN,
                        null));
        assertThrows(IllegalArgumentException.class,
                () -> new DetectionEvent(id, streamId, assetId, "person", 0.5, null, T0, DetectionEventState.OPEN,
                        null));
        assertThrows(IllegalArgumentException.class,
                () -> new DetectionEvent(id, streamId, assetId, "person", 0.5, T0, null, DetectionEventState.OPEN,
                        null));
        assertThrows(IllegalArgumentException.class,
                () -> new DetectionEvent(id, streamId, assetId, "person", 0.5, T0, T0.minusSeconds(1),
                        DetectionEventState.OPEN, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DetectionEvent(id, streamId, assetId, "person", 0.5, T0, T0, null, null));
    }

    @Test
    void nullAssetIdAndPositionAreAllowed() {
        DetectionEvent event = new DetectionEvent(DetectionEventId.random(), streamId, null, "person", 0.5, T0, T0,
                DetectionEventState.OPEN, null);

        assertNull(event.assetId());
        assertNull(event.position());
    }

    @Test
    void lastSeenEqualToFirstSeenIsAllowed() {
        DetectionEvent event = event(T0, T0);

        assertEquals(T0, event.firstSeen());
        assertEquals(T0, event.lastSeen());
    }

    @Test
    void withObservationAdvancesLastSeenAndRaisesPeakConfidence() {
        DetectionEvent original = event(T0, T0);

        DetectionEvent higher = original.withObservation(T0.plusSeconds(1), 0.9);
        assertEquals(T0.plusSeconds(1), higher.lastSeen());
        assertEquals(0.9, higher.peakConfidence());
        assertEquals(T0, higher.firstSeen(), "firstSeen must stay pinned to the original open time");
        assertEquals(DetectionEventState.OPEN, higher.state());

        DetectionEvent lower = higher.withObservation(T0.plusSeconds(2), 0.3);
        assertEquals(T0.plusSeconds(2), lower.lastSeen());
        assertEquals(0.9, lower.peakConfidence(), "peakConfidence must never decrease");
    }

    @Test
    void closedFlipsStateButLeavesLastSeenUntouched() {
        DetectionEvent open = event(T0, T0.plusSeconds(2));

        DetectionEvent closed = open.closed();

        assertEquals(DetectionEventState.CLOSED, closed.state());
        assertEquals(T0.plusSeconds(2), closed.lastSeen(), "closed() must not move lastSeen forward");
        assertEquals(open.firstSeen(), closed.firstSeen());
        assertEquals(open.peakConfidence(), closed.peakConfidence());
    }
}

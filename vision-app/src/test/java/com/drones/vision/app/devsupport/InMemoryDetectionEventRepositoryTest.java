package com.drones.vision.app.devsupport;

import com.drones.vision.perception.domain.model.DetectionEvent;
import com.drones.vision.perception.domain.model.DetectionEventId;
import com.drones.vision.perception.domain.model.DetectionEventState;
import com.drones.vision.kernel.StreamId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryDetectionEventRepositoryTest {

    private static DetectionEvent event(StreamId streamId, String label, Instant firstSeen, Instant lastSeen,
                                         DetectionEventState state) {
        return new DetectionEvent(DetectionEventId.random(), streamId, null, label, 0.87, firstSeen, lastSeen,
                state, null);
    }

    @Test
    void findByStreamReturnsSavedEventsForTheirOwnStreamOnly() {
        InMemoryDetectionEventRepository repository = new InMemoryDetectionEventRepository();
        StreamId streamA = StreamId.random();
        StreamId streamB = StreamId.random();
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        DetectionEvent onA = event(streamA, "person", t0, t0, DetectionEventState.OPEN);
        DetectionEvent onB = event(streamB, "car", t0, t0, DetectionEventState.OPEN);
        repository.save(onA);
        repository.save(onB);

        List<DetectionEvent> forA = repository.findByStream(streamA, 10);

        assertEquals(List.of(onA), forA);
    }

    @Test
    void findByStreamReturnsNewestFirstBySaveOrderIndependentLastSeen() {
        InMemoryDetectionEventRepository repository = new InMemoryDetectionEventRepository();
        StreamId streamId = StreamId.random();
        Instant older = Instant.parse("2026-01-01T00:00:00Z");
        Instant newer = Instant.parse("2026-01-01T00:00:10Z");
        DetectionEvent olderEvent = event(streamId, "person", older, older, DetectionEventState.OPEN);
        DetectionEvent newerEvent = event(streamId, "car", newer, newer, DetectionEventState.OPEN);
        // Saved in the "wrong" order on purpose -- ordering must come from lastSeen, not save order.
        repository.save(newerEvent);
        repository.save(olderEvent);

        List<DetectionEvent> results = repository.findByStream(streamId, 10);

        assertEquals(List.of(newerEvent, olderEvent), results);
    }

    @Test
    void saveUpsertsByIdRatherThanDuplicating() {
        InMemoryDetectionEventRepository repository = new InMemoryDetectionEventRepository();
        StreamId streamId = StreamId.random();
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        DetectionEventId id = DetectionEventId.random();
        DetectionEvent opened =
                new DetectionEvent(id, streamId, null, "person", 0.6, t0, t0, DetectionEventState.OPEN, null);
        DetectionEvent updated = opened.withObservation(t0.plusSeconds(1), 0.9);

        repository.save(opened);
        repository.save(updated);

        List<DetectionEvent> results = repository.findByStream(streamId, 10);
        assertEquals(1, results.size(), "an id seen before must replace, not duplicate");
        assertEquals(t0.plusSeconds(1), results.get(0).lastSeen());
        assertEquals(0.9, results.get(0).peakConfidence());
    }

    @Test
    void findRecentSpansAllStreamsAndFiltersBySince() {
        InMemoryDetectionEventRepository repository = new InMemoryDetectionEventRepository();
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t1 = Instant.parse("2026-01-01T00:01:00Z");
        DetectionEvent old = event(StreamId.random(), "person", t0, t0, DetectionEventState.CLOSED);
        DetectionEvent fresh = event(StreamId.random(), "car", t1, t1, DetectionEventState.OPEN);
        repository.save(old);
        repository.save(fresh);

        List<DetectionEvent> all = repository.findRecent(null, 10);
        assertEquals(List.of(fresh, old), all);

        List<DetectionEvent> sinceT1 = repository.findRecent(t1, 10);
        assertEquals(List.of(fresh), sinceT1);
    }

    @Test
    void findByStreamReturnsEmptyForAnUnknownStream() {
        InMemoryDetectionEventRepository repository = new InMemoryDetectionEventRepository();

        assertEquals(List.of(), repository.findByStream(StreamId.random(), 10));
    }

    @Test
    void oldestEventIsEvictedOncePerStreamCapIsExceeded() {
        InMemoryDetectionEventRepository repository = new InMemoryDetectionEventRepository();
        StreamId streamId = StreamId.random();
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        int overCap = InMemoryDetectionEventRepository.MAX_EVENTS_PER_STREAM + 5;
        for (int i = 0; i < overCap; i++) {
            Instant at = base.plusSeconds(i);
            repository.save(event(streamId, "label-" + i, at, at, DetectionEventState.CLOSED));
        }

        List<DetectionEvent> results = repository.findByStream(streamId, overCap);

        assertEquals(InMemoryDetectionEventRepository.MAX_EVENTS_PER_STREAM, results.size());
        assertEquals("label-" + (overCap - 1), results.get(0).label()); // newest kept
        assertEquals("label-5", results.getLast().label()); // oldest still retained after eviction
        assertTrue(results.stream().noneMatch(e -> e.label().equals("label-0")));
    }
}

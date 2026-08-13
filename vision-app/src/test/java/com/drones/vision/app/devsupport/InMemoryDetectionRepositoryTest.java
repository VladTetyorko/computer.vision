package com.drones.vision.app.devsupport;

import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.perception.domain.model.DetectionQuery;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.kernel.StreamId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryDetectionRepositoryTest {

    private static DetectionResult result(StreamId streamId, long sequence, Instant capturedAt, String label) {
        Detection detection = new Detection(label, 0.9, new BoundingBox(0.1, 0.1, 0.2, 0.2),
                new ModelRef("yolo", "latest"));
        return new DetectionResult(streamId, sequence, capturedAt, List.of(detection), Duration.ofMillis(5));
    }

    @Test
    void queryReturnsSavedResultsForTheirOwnStreamOnly() {
        InMemoryDetectionRepository repository = new InMemoryDetectionRepository();
        StreamId streamA = StreamId.random();
        StreamId streamB = StreamId.random();
        DetectionResult onA = result(streamA, 0, Instant.now(), "person");
        DetectionResult onB = result(streamB, 0, Instant.now(), "car");
        repository.save(onA);
        repository.save(onB);

        List<DetectionResult> forA = repository.query(new DetectionQuery(streamA, null, null, null, 10));

        assertEquals(List.of(onA), forA);
    }

    @Test
    void queryReturnsNewestFirstRegardlessOfSaveOrder() {
        InMemoryDetectionRepository repository = new InMemoryDetectionRepository();
        StreamId streamId = StreamId.random();
        Instant older = Instant.parse("2026-01-01T00:00:00Z");
        Instant newer = Instant.parse("2026-01-01T00:00:10Z");
        DetectionResult olderResult = result(streamId, 0, older, "person");
        DetectionResult newerResult = result(streamId, 1, newer, "person");
        repository.save(olderResult);
        repository.save(newerResult);

        List<DetectionResult> results = repository.query(new DetectionQuery(streamId, null, null, null, 10));

        assertEquals(List.of(newerResult, olderResult), results);
    }

    @Test
    void queryFiltersByLabelAndTimeRange() {
        InMemoryDetectionRepository repository = new InMemoryDetectionRepository();
        StreamId streamId = StreamId.random();
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t1 = Instant.parse("2026-01-01T00:01:00Z");
        Instant t2 = Instant.parse("2026-01-01T00:02:00Z");
        DetectionResult person = result(streamId, 0, t0, "person");
        DetectionResult car = result(streamId, 1, t1, "car");
        DetectionResult laterPerson = result(streamId, 2, t2, "person");
        repository.save(person);
        repository.save(car);
        repository.save(laterPerson);

        List<DetectionResult> onlyPerson =
                repository.query(new DetectionQuery(streamId, null, null, "person", 10));
        assertEquals(List.of(laterPerson, person), onlyPerson);

        List<DetectionResult> windowed =
                repository.query(new DetectionQuery(streamId, t0.plusSeconds(1), t1.plusSeconds(1), null, 10));
        assertEquals(List.of(car), windowed);
    }

    @Test
    void queryAcrossAllStreamsWhenStreamIdIsNull() {
        InMemoryDetectionRepository repository = new InMemoryDetectionRepository();
        DetectionResult onA = result(StreamId.random(), 0, Instant.now(), "person");
        DetectionResult onB = result(StreamId.random(), 0, Instant.now(), "car");
        repository.save(onA);
        repository.save(onB);

        List<DetectionResult> all = repository.query(new DetectionQuery(null, null, null, null, 10));

        assertEquals(2, all.size());
    }

    @Test
    void queryReturnsEmptyForAnUnknownStream() {
        InMemoryDetectionRepository repository = new InMemoryDetectionRepository();

        List<DetectionResult> results =
                repository.query(new DetectionQuery(StreamId.random(), null, null, null, 10));

        assertEquals(List.of(), results);
    }

    @Test
    void oldestResultIsEvictedOncePerStreamCapIsExceeded() {
        InMemoryDetectionRepository repository = new InMemoryDetectionRepository();
        StreamId streamId = StreamId.random();
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        int overCap = InMemoryDetectionRepository.MAX_RESULTS_PER_STREAM + 5;
        for (int i = 0; i < overCap; i++) {
            repository.save(result(streamId, i, base.plusSeconds(i), "person"));
        }

        List<DetectionResult> results =
                repository.query(new DetectionQuery(streamId, null, null, null, overCap));

        assertEquals(InMemoryDetectionRepository.MAX_RESULTS_PER_STREAM, results.size());
        // The 5 oldest (sequence 0..4) were evicted; the newest-first order's last element is the
        // oldest still retained (sequence 5).
        assertEquals(overCap - 1, results.get(0).frameSequence()); // newest kept
        assertEquals(5, results.getLast().frameSequence()); // oldest still retained after eviction
        assertTrue(results.stream().noneMatch(r -> r.frameSequence() < 5));
    }
}

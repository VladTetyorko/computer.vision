package com.drones.vision.app.devsupport;

import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.DetectionQuery;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.events.domain.port.DetectionRepositoryPort;

import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Stream;

/**
 * In-memory {@link DetectionRepositoryPort}: dev/Phase-0 fallback with no
 * durability across restarts.
 *
 * <p>Append-only ring per stream: each stream's results are kept in their own
 * bounded deque, capped at {@value #MAX_RESULTS_PER_STREAM} entries, oldest
 * evicted first — a long-running demo stream can never grow this
 * unboundedly. {@link #query} always sorts newest-first before applying
 * {@link DetectionQuery#limit()} (the port's contract does not guarantee an
 * order, so this is a devsupport-adapter choice, not a documented port
 * behavior — {@code vision-api}'s {@code StreamController} sorts again
 * defensively rather than relying on it).
 *
 * <p>Replaced by {@code adapter-persistence} (JPA/Postgres), planned for
 * Phase 2.
 */
public final class InMemoryDetectionRepository implements DetectionRepositoryPort {

    /** Cap on how many results are retained per stream before the oldest is evicted. */
    static final int MAX_RESULTS_PER_STREAM = 1000;

    private final Map<StreamId, Deque<DetectionResult>> resultsByStream = new ConcurrentHashMap<>();

    @Override
    public void save(DetectionResult result) {
        Deque<DetectionResult> deque =
                resultsByStream.computeIfAbsent(result.streamId(), id -> new ConcurrentLinkedDeque<>());
        synchronized (deque) {
            deque.addLast(result);
            while (deque.size() > MAX_RESULTS_PER_STREAM) {
                deque.pollFirst();
            }
        }
    }

    @Override
    public List<DetectionResult> query(DetectionQuery query) {
        Stream<DetectionResult> candidates = query.streamId() != null
                ? resultsFor(query.streamId())
                : resultsByStream.keySet().stream().flatMap(this::resultsFor);
        return candidates
                .filter(r -> query.from() == null || !r.capturedAt().isBefore(query.from()))
                .filter(r -> query.to() == null || !r.capturedAt().isAfter(query.to()))
                .filter(r -> query.label() == null
                        || r.detections().stream().anyMatch(d -> query.label().equals(d.label())))
                .sorted(Comparator.comparing(DetectionResult::capturedAt).reversed())
                .limit(query.limit())
                .toList();
    }

    private Stream<DetectionResult> resultsFor(StreamId streamId) {
        Deque<DetectionResult> deque = resultsByStream.get(streamId);
        if (deque == null) {
            return Stream.empty();
        }
        synchronized (deque) {
            return List.copyOf(deque).stream(); // snapshot: safe to iterate after releasing the lock
        }
    }
}

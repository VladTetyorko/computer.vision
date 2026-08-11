package com.drones.vision.app.devsupport;

import com.drones.vision.perception.domain.model.DetectionEvent;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.port.DetectionEventRepositoryPort;

import java.time.Instant;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Stream;

/**
 * In-memory {@link DetectionEventRepositoryPort}: dev/Phase-0 fallback with no durability across
 * restarts — same posture as {@link InMemoryDetectionRepository} (docs/plans/done/MVP2-PLAN.md §E, E-a).
 *
 * <p>Ring per stream: each stream's events live in their own bounded deque, capped at {@value
 * #MAX_EVENTS_PER_STREAM} entries, oldest evicted first once full. Unlike {@link
 * InMemoryDetectionRepository#save}, which only ever appends immutable historical rows, {@link
 * #save} here is a genuine <b>upsert</b> keyed by {@link DetectionEvent#id()} — a {@code
 * DetectionEvent} mutates over its own open lifetime ({@code lastSeen}/{@code peakConfidence}
 * advancing, then a final close), so an id seen before is removed and re-added at the tail rather
 * than duplicated. Re-adding at the tail on every update is deliberate, not incidental: it means a
 * frequently-updated, still-{@code OPEN} event is naturally protected from the eviction cap below
 * — only entries that genuinely stop being touched (an event long since closed, or an
 * ever-growing count of distinct labels/streams) age toward the front and risk eviction.
 *
 * <p>{@link #findRecent}/{@link #findByStream} both sort newest-first by {@link
 * DetectionEvent#lastSeen()} before applying {@code limit} — an explicit sort, not a reliance on
 * deque order — mirroring {@link InMemoryDetectionRepository#query}'s own "devsupport-adapter
 * choice, not a documented port guarantee" precedent.
 *
 * <p><b>Known gap, same shape as the ring cap above</b>: at {@value #MAX_EVENTS_PER_STREAM} *
 * distinct* events retained per stream (not per label — every label's own open+eventual-close pair
 * counts toward the same cap), a stream that opens/closes an unusually large number of distinct
 * events over its lifetime will silently lose its oldest ones. Documented rather than engineered
 * around, consistent with this module's existing devsupport postures (see {@link
 * InMemoryDetectionRepository}'s own ring cap for the identical tradeoff).
 *
 * <p>Replaced by {@code adapter-persistence} (JPA/Postgres) — explicitly deferred for this feature
 * (docs/plans/done/MVP2-PLAN.md §E, E-a's own scope boundary: domain/application/api only), planned for a
 * future persistence cycle alongside the other repository ports it already covers.
 */
public final class InMemoryDetectionEventRepository implements DetectionEventRepositoryPort {

    /** Cap on how many events are retained per stream before the oldest is evicted. */
    static final int MAX_EVENTS_PER_STREAM = 500;

    private final Map<StreamId, Deque<DetectionEvent>> eventsByStream = new ConcurrentHashMap<>();

    @Override
    public DetectionEvent save(DetectionEvent event) {
        Deque<DetectionEvent> deque =
                eventsByStream.computeIfAbsent(event.streamId(), id -> new ConcurrentLinkedDeque<>());
        synchronized (deque) {
            deque.removeIf(e -> e.id().equals(event.id()));
            deque.addLast(event);
            while (deque.size() > MAX_EVENTS_PER_STREAM) {
                deque.pollFirst();
            }
        }
        return event;
    }

    @Override
    public List<DetectionEvent> findRecent(Instant sinceInclusive, int limit) {
        Stream<DetectionEvent> all = eventsByStream.keySet().stream().flatMap(this::eventsFor);
        return newestFirst(all, sinceInclusive, limit);
    }

    @Override
    public List<DetectionEvent> findByStream(StreamId streamId, int limit) {
        return newestFirst(eventsFor(streamId), null, limit);
    }

    private static List<DetectionEvent> newestFirst(Stream<DetectionEvent> candidates, Instant sinceInclusive,
                                                      int limit) {
        return candidates
                .filter(e -> sinceInclusive == null || !e.lastSeen().isBefore(sinceInclusive))
                .sorted(Comparator.comparing(DetectionEvent::lastSeen).reversed())
                .limit(limit)
                .toList();
    }

    private Stream<DetectionEvent> eventsFor(StreamId streamId) {
        Deque<DetectionEvent> deque = eventsByStream.get(streamId);
        if (deque == null) {
            return Stream.empty();
        }
        synchronized (deque) {
            return List.copyOf(deque).stream(); // snapshot: safe to iterate after releasing the lock
        }
    }
}

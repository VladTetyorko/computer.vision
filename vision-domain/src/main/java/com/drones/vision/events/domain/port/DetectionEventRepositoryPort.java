package com.drones.vision.events.domain.port;

import com.drones.vision.events.domain.model.DetectionEvent;
import com.drones.vision.kernel.StreamId;

import java.time.Instant;
import java.util.List;

/**
 * Driven port: persist and query debounced {@code DetectionEvent}s (docs/plans/done/MVP2-PLAN.md §E, E-a).
 *
 * <p>Unlike {@link DetectionRepositoryPort} (append-only, one immutable row per completed
 * inference), a {@code DetectionEvent} mutates over its own open lifetime — {@code lastSeen}/
 * {@code peakConfidence} advance while it stays open, then it closes — so {@link #save} is a
 * genuine upsert keyed by the event's own id, not an append.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #save(DetectionEvent)} upserts by {@link DetectionEvent#id()}: an id seen before is
 *       replaced in place (an open→open update, or an open→closed transition); a new id is
 *       added.</li>
 *   <li>{@link #findRecent(Instant, int)} returns the newest events across every stream, ordered
 *       by {@link DetectionEvent#lastSeen()} descending, bounded to {@code limit}. {@code
 *       sinceInclusive} is nullable — {@code null} means no lower bound; non-null excludes any
 *       event whose {@code lastSeen} is strictly before it (a polling cursor: a caller remembers
 *       the newest {@code lastSeen} it has already seen and passes it back in on the next
 *       poll).</li>
 *   <li>{@link #findByStream(StreamId, int)} returns one stream's events, same ordering, same
 *       bound; an unknown/streamless-so-far stream yields an empty list, not an error.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use: {@code save} is called from the per-stream
 * pipeline thread (potentially many streams at once, each with their own {@code
 * DetectionEventEngine}), while {@code findRecent}/{@code findByStream} may be called
 * concurrently from control-plane reads (polling {@code GET /api/events}).
 */
public interface DetectionEventRepositoryPort {

    /**
     * Upserts an event by id.
     *
     * @param event the event to persist
     * @return the persisted event (implementations may return {@code event} itself)
     */
    DetectionEvent save(DetectionEvent event);

    /**
     * Lists the most recent events across every stream, newest-first by {@link
     * DetectionEvent#lastSeen()}.
     *
     * @param sinceInclusive only events whose {@code lastSeen} is at or after this instant, or
     *                       {@code null} for no lower bound
     * @param limit          maximum number of events to return; must be positive
     * @return an immutable snapshot, newest-first
     */
    List<DetectionEvent> findRecent(Instant sinceInclusive, int limit);

    /**
     * Lists one stream's most recent events, newest-first by {@link DetectionEvent#lastSeen()}.
     *
     * @param streamId the stream to inspect
     * @param limit    maximum number of events to return; must be positive
     * @return an immutable snapshot, newest-first; empty if the stream has no events
     */
    List<DetectionEvent> findByStream(StreamId streamId, int limit);
}

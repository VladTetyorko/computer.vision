package com.drones.vision.platform;

import java.time.Instant;
import java.util.List;

/**
 * Driven port: a durable, queryable home for platform {@link Event}s.
 *
 * <p>{@link EventPublisherPort} is fire-and-forget delivery to whoever is listening right now
 * (today: logging, the {@code event} SSE topic); nothing before this port gave an {@link Event} a
 * life longer than that one delivery, so the notification bell and {@code /manage/system} — both a
 * pure {@code computed} over the live feed's in-memory log — started empty on every page load and
 * lost everything on an SSE reconnect (docs/plans/active/ALWAYS-ON-FLOW-PLAN.md wave B3). This port
 * is that missing durable store, modeled on {@link AuditTrailPort}'s query surface and no wider —
 * an append plus two bounded, newest-first reads, nothing more.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #record(Event)} appends an event. Recorded events are immutable historical facts —
 *       never updated, never deleted except by an implementation's own retention policy.</li>
 *   <li>{@link #findRecent(int)} returns the newest recorded events across every type and stream,
 *       bounded to {@code limit}, newest first.</li>
 *   <li>{@link #findSince(Instant, int)} returns the newest recorded events at or after a cursor
 *       instant, newest first, bounded to {@code limit} — the shape a reconnecting caller needs:
 *       "what did I miss since the last event I already have."</li>
 * </ul>
 *
 * <p><b>Not every {@link Event} necessarily reaches this port at all.</b> Unlike {@link
 * AuditTrailPort} (every audited action is recorded), a durable implementation is free to be
 * selective about volume — {@link EventType#DETECTION} in particular is high-frequency, hot-path
 * output already durable elsewhere (a stream's {@code DetectionRepositoryPort}/{@code
 * DetectionEventRepositoryPort}), so recording it a second time here would buy nothing but write
 * load. Callers must not assume {@link #findRecent}/{@link #findSince} are a complete replay of
 * every {@link Event} ever published — only that what they do return is durable and ordered.
 *
 * <p>Recording must never break the operation that raised the event — the same posture {@link
 * EventPublisherPort#publish(Event)} itself requires; losing a history row is strictly better than
 * failing whatever pipeline work raised the event. This port's own implementations (e.g. a JPA
 * adapter) are free to throw normally on a genuine failure, the same as any other repository-shaped
 * port — {@code record} is not itself required to swallow exceptions. The "never propagate" half of
 * the contract instead falls on whoever calls {@code record} off the hot path (a decorator on {@link
 * EventPublisherPort}, not a direct pipeline caller): it must isolate this port's failures from the
 * operation that raised the event, exactly as it isolates the caller from this port's I/O latency in
 * the first place.
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use: {@link #record(Event)} may be called from many
 * stream pipelines and control-plane operations at once, while reads happen concurrently from
 * control-plane polling.
 */
public interface EventHistoryPort {

    /**
     * Appends an event to the durable history.
     *
     * @param event the event to record
     * @return the recorded event (implementations may return {@code event} itself)
     */
    Event record(Event event);

    /**
     * Lists the most recent recorded events across everything, newest first.
     *
     * @param limit maximum number of events to return; must be positive
     * @return an immutable snapshot, newest first
     */
    List<Event> findRecent(int limit);

    /**
     * Lists the most recent recorded events at or after a cursor instant, newest first — the
     * "what changed since I last asked" query a reconnecting live-feed client uses to backfill the
     * gap an SSE drop left.
     *
     * @param sinceInclusive only events whose {@code at} is at or after this instant, or {@code
     *                       null} for no lower bound
     * @param limit          maximum number of events to return; must be positive
     * @return an immutable snapshot, newest first
     */
    List<Event> findSince(Instant sinceInclusive, int limit);
}

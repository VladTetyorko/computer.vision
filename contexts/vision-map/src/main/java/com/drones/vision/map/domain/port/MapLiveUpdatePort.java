package com.drones.vision.map.domain.port;

import com.drones.vision.map.domain.model.MapEvent;

/**
 * Driven port: announce a {@link MapEvent} live, so a driving adapter can push it to connected
 * viewers, scoped per connection by which layers its viewer may see
 * (docs/plans/done/MAP-REWORK-PLAN.md §2.3/§4.3 — the security-critical rework that scoped
 * delivery requires).
 *
 * <p>This is the application layer's <em>only</em> notion of "someone might be watching right
 * now" for the tactical map — it knows nothing about SSE, connections, topics, or resume/replay;
 * those are entirely a driving adapter's concern (today, {@code vision-api}'s {@code /api/live}
 * registry). One of five ports this context's slice of the former god-port {@code
 * LiveUpdatePublisherPort} split into (docs/plans/active/DOMAIN-SEPARATION-W1.md §15, W1.6b) —
 * each publishing context now owns exactly the payload it produces. Supersedes that port's even
 * earlier {@code publishMarkCreated}/{@code publishMarkUpdated}/{@code publishMarkCleared} trio
 * (docs/plans/done/TACTICAL-MARKS-PLAN.md §5) — one method here covers marks, drawings, and
 * layers alike.
 *
 * <h2>Contract</h2>
 * Must return quickly and must not throw for an ordinary delivery failure — a disconnected
 * viewer, a full connection registry, or the feature being disabled entirely (a no-op
 * implementation) must never surface as an exception on the caller's own hot path.
 *
 * <h2>Threading</h2>
 * Called whenever a mark, drawing, or layer is created/updated/cleared/deleted; implementations
 * must be cheap and effectively fire-and-forget: hand off to a background dispatcher for any real
 * I/O (serializing a payload, writing to a connection, resolving per-connection layer visibility)
 * rather than doing it on the calling thread. Safe for concurrent use.
 */
public interface MapLiveUpdatePort {

    /**
     * Announces a {@link MapEvent} — a mark, drawing, or layer created, updated, cleared, or
     * deleted (docs/plans/done/MAP-REWORK-PLAN.md §2.3). Unlike every other live-update method,
     * delivery here is scoped per-connection by which layers a viewer may see ({@code
     * MapAccessPolicy}) rather than broadcast to everyone — the security-critical rework that
     * scoped delivery requires (docs/plans/done/MAP-REWORK-PLAN.md §4.3), so an implementor must
     * engage with it rather than silently no-op.
     *
     * @param event the map event that occurred
     */
    void publishMapEvent(MapEvent event);
}

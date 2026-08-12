package com.drones.vision.warehouse.domain.port;

/**
 * Driven port: announce that fleet-level state changed, so a driving adapter can push it to
 * connected viewers (docs/plans/done/REALTIME-PLAN.md §4 — the server-push data plane replacing
 * steady-state polling).
 *
 * <p>This is the application layer's <em>only</em> notion of "someone might be watching right
 * now" for fleet inventory — it knows nothing about SSE, connections, topics, or resume/replay;
 * those are entirely a driving adapter's concern (today, {@code vision-api}'s {@code /api/live}
 * registry). One of five ports this context's slice of the former god-port {@code
 * LiveUpdatePublisherPort} split into (docs/plans/active/DOMAIN-SEPARATION-W1.md §15, W1.6b) —
 * each publishing context now owns exactly the payload it produces.
 *
 * <h2>Contract</h2>
 * Must return quickly and must not throw for an ordinary delivery failure — a disconnected
 * viewer, a full connection registry, or the feature being disabled entirely (a no-op
 * implementation) must never surface as an exception on the caller's own hot path.
 *
 * <h2>Threading</h2>
 * Called from asset/device/stream lifecycle operations whenever fleet-level state changes;
 * implementations must be cheap and effectively fire-and-forget: hand off to a background
 * dispatcher for any real I/O (serializing a payload, writing to a connection) rather than doing
 * it on the calling thread. Safe for concurrent use from many callers at once.
 */
public interface FleetLiveUpdatePort {

    /**
     * Announces that fleet-level state changed — an asset, device, or stream's lifecycle
     * (created, edited, soft-deleted/restored, started, stopped). Carries no payload; a driving
     * adapter that wants to push a fresh snapshot re-derives it from the same driving services a
     * REST client would call.
     */
    void publishFleetChanged();
}

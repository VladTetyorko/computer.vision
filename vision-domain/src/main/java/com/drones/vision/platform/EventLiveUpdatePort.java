package com.drones.vision.platform;

/**
 * Driven port: announce a domain {@link Event} live, so a driving adapter can push it to
 * connected viewers (docs/plans/done/REALTIME-PLAN.md §4 — the server-push data plane replacing
 * steady-state polling).
 *
 * <p>This is the application layer's <em>only</em> notion of "someone might be watching right
 * now" for raw domain events (device online/offline, stream started/stopped, pipeline errors,
 * geofence breaches, ...) — it knows nothing about SSE, connections, topics, or resume/replay;
 * those are entirely a driving adapter's concern (today, {@code vision-api}'s {@code /api/live}
 * registry). One of five ports the former god-port {@code LiveUpdatePublisherPort} split into
 * (docs/plans/active/DOMAIN-SEPARATION-W1.md §15, W1.6b) — filed here in {@code platform} rather
 * than any one bounded context because every context raises an {@link Event} and {@link Event}
 * itself already lives here as a cross-cutting seam (W1.6a).
 *
 * <h2>Contract</h2>
 * Must return quickly and must not throw for an ordinary delivery failure (mirrors {@link
 * EventPublisherPort}'s own contract) — a disconnected viewer, a full connection registry, or the
 * feature being disabled entirely (a no-op implementation) must never surface as an exception on
 * the caller's own hot path.
 *
 * <h2>Threading</h2>
 * Called from every context's hot paths that raise an {@link Event} (the stream pipeline on
 * detections, telemetry sampling, geofence breach evaluation, ...); implementations must be cheap
 * and effectively fire-and-forget: hand off to a background dispatcher for any real I/O
 * (serializing a payload, writing to a connection) rather than doing it on the calling thread.
 * Safe for concurrent use from many callers at once.
 */
public interface EventLiveUpdatePort {

    /**
     * Announces a domain {@link Event}.
     *
     * @param event the event that was raised
     */
    void publishEvent(Event event);
}

package com.drones.vision.flight.domain.port;

import com.drones.vision.flight.domain.model.Telemetry;
import com.drones.vision.kernel.AssetId;

/**
 * Driven port: announce that a telemetry sample was appended, so a driving adapter can push it to
 * connected viewers (docs/plans/done/REALTIME-PLAN.md §4 — the server-push data plane replacing
 * steady-state polling).
 *
 * <p>This is the application layer's <em>only</em> notion of "someone might be watching right
 * now" for live telemetry — it knows nothing about SSE, connections, topics, or resume/replay;
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
 * Called from telemetry sampling ({@code UsageTracker}, once per appended sample) — a hot path —
 * so implementations must be cheap and effectively fire-and-forget: hand off to a background
 * dispatcher for any real I/O (serializing a payload, writing to a connection) rather than doing
 * it on the calling thread. Safe for concurrent use from many assets at once.
 */
public interface TelemetryLiveUpdatePort {

    /**
     * Announces that one telemetry sample was appended to an asset's currently open usage.
     *
     * @param assetId the asset the sample belongs to
     * @param sample  the newly appended sample
     */
    void publishTelemetryAppended(AssetId assetId, Telemetry sample);
}

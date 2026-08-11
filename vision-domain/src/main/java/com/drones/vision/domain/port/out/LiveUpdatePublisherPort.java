package com.drones.vision.domain.port.out;

import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.DetectionEvent;
import com.drones.vision.domain.model.DetectionResult;
import com.drones.vision.domain.model.Event;
import com.drones.vision.domain.model.MapEvent;
import com.drones.vision.domain.model.Telemetry;

/**
 * Driven port: announce a live update so a driving adapter can push it to connected viewers
 * (docs/plans/done/REALTIME-PLAN.md §4 — the server-push data plane replacing steady-state polling).
 *
 * <p>This is the application layer's <em>only</em> notion of "someone might be watching right
 * now" — it knows nothing about SSE, connections, topics, or resume/replay; those are entirely a
 * driving adapter's concern (today, {@code vision-api}'s {@code /api/live} registry). Callers
 * simply announce facts as they already happen at their natural seam:
 * <ul>
 *   <li>{@link #publishFleetChanged()} — an asset, device, or stream's lifecycle state changed
 *       (created/updated/deleted/started/stopped). No payload: the adapter decides what a fresh
 *       fleet snapshot looks like and fetches it itself (through the same driving services a
 *       REST client already uses), so this port never has to shadow that read model.</li>
 *   <li>{@link #publishTelemetryAppended(AssetId, Telemetry)} — one telemetry sample was just
 *       appended to an asset's open usage.</li>
 *   <li>{@link #publishDetections(AssetId, DetectionResult)} — one stream's inference completed
 *       (including an empty result — "nothing detected now" is itself useful live information),
 *       attributed to the stream's owning asset.</li>
 *   <li>{@link #publishEvent(Event)} — a domain {@link Event} was raised (device online/offline,
 *       stream started/stopped, pipeline errors, ...).</li>
 *   <li>{@link #publishDetectionEvent(DetectionEvent)} — a debounced {@link DetectionEvent} opened,
 *       advanced (a further qualifying observation while already open), or closed — the same
 *       occurrence {@code DetectionEventRepositoryPort#save} already persists, announced at the
 *       exact same seam rather than duplicated bookkeeping.</li>
 *   <li>{@link #publishMapEvent(MapEvent)} — a {@link MapEvent} (docs/plans/done/MAP-REWORK-PLAN.md §2.3):
 *       a mark, drawing, or layer was created/updated/cleared/deleted. Supersedes this port's
 *       former {@code publishMarkCreated}/{@code publishMarkUpdated}/{@code publishMarkCleared}
 *       trio (docs/plans/done/TACTICAL-MARKS-PLAN.md §5) — one method now covers marks, drawings, and layers
 *       alike. A driving adapter broadcasts this on a single {@code "map"} SSE topic, filtered per
 *       connection by which layers its viewer may see ({@code MapAccessPolicy}) — the
 *       security-critical rework that scoped delivery requires, unlike every other method on this
 *       port, which still broadcasts to everyone.</li>
 * </ul>
 *
 * <h2>Contract</h2>
 * Every method must return quickly and must not throw for an ordinary delivery failure (ADR
 * mirrors {@link EventPublisherPort}'s own contract) — a disconnected viewer, a full connection
 * registry, or the feature being disabled entirely (a no-op implementation) must never surface as
 * an exception on the caller's own hot path.
 *
 * <h2>Threading</h2>
 * Called from the hot stream-pipeline path ({@code StreamPipeline}, once per completed inference)
 * and from telemetry sampling ({@code UsageTracker}, once per appended sample), so implementations
 * must be cheap and effectively fire-and-forget: hand off to a background dispatcher for any real
 * I/O (serializing a payload, writing to a connection) rather than doing it on the calling thread.
 * Safe for concurrent use from many streams/assets at once.
 */
public interface LiveUpdatePublisherPort {

    /**
     * Announces that fleet-level state changed — an asset, device, or stream's lifecycle
     * (created, edited, soft-deleted/restored, started, stopped). Carries no payload; a driving
     * adapter that wants to push a fresh snapshot re-derives it from the same driving services a
     * REST client would call.
     */
    void publishFleetChanged();

    /**
     * Announces that one telemetry sample was appended to an asset's currently open usage.
     *
     * @param assetId the asset the sample belongs to
     * @param sample  the newly appended sample
     */
    void publishTelemetryAppended(AssetId assetId, Telemetry sample);

    /**
     * Announces that one stream's inference completed, attributed to the stream's owning asset.
     *
     * @param assetId the asset that owns the stream this result belongs to
     * @param result  the completed detection result, including an empty one
     */
    void publishDetections(AssetId assetId, DetectionResult result);

    /**
     * Announces a domain {@link Event}.
     *
     * @param event the event that was raised
     */
    void publishEvent(Event event);

    /**
     * Announces a debounced {@link DetectionEvent} occurrence — opened, advanced while already
     * open, or closed. Carries the event itself (unlike {@link #publishFleetChanged()}'s no-payload
     * shape) since a driving adapter has no cheaper way to re-derive "which event, in which state"
     * than being told directly, exactly mirroring how {@link #publishTelemetryAppended}/{@link
     * #publishDetections} already carry their own payload rather than a bare notification.
     *
     * @param event the event's current state
     */
    void publishDetectionEvent(DetectionEvent event);

    /**
     * Announces a {@link MapEvent} — a mark, drawing, or layer created, updated, cleared, or
     * deleted (docs/plans/done/MAP-REWORK-PLAN.md §2.3). Replaces the three mark-specific {@code
     * publishMarkCreated}/{@code publishMarkUpdated}/{@code publishMarkCleared} methods this port
     * had under docs/plans/done/TACTICAL-MARKS-PLAN.md §5 (one method here now covers marks, drawings, and
     * layers alike). Unlike those, this method is <b>not</b> {@code default}-bodied: scoped
     * per-connection delivery is the point of this rework (docs/plans/done/MAP-REWORK-PLAN.md §4.3), so a
     * driving adapter must implement it deliberately rather than silently no-op.
     *
     * @param event the map event that occurred
     */
    void publishMapEvent(MapEvent event);
}

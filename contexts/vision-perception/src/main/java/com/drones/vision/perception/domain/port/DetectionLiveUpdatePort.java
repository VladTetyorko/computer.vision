package com.drones.vision.perception.domain.port;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.perception.domain.model.DetectionEvent;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.WorldObject;

import java.util.List;

/**
 * Driven port: announce a stream's live detection activity — a completed inference or a debounced
 * {@link DetectionEvent} transition — so a driving adapter can push it to connected viewers
 * (docs/plans/done/REALTIME-PLAN.md §4 — the server-push data plane replacing steady-state
 * polling; docs/plans/done/MVP2-PLAN.md §E's backend follow-ups batch added the {@code
 * DetectionEvent} half).
 *
 * <p>This is the application layer's <em>only</em> notion of "someone might be watching right
 * now" for live detections — it knows nothing about SSE, connections, topics, or resume/replay;
 * those are entirely a driving adapter's concern (today, {@code vision-api}'s {@code /api/live}
 * registry). One of five ports this context's slice of the former god-port {@code
 * LiveUpdatePublisherPort} split into (docs/plans/active/DOMAIN-SEPARATION-W1.md §15, W1.6b) —
 * each publishing context now owns exactly the payload it produces.
 *
 * <h2>Contract</h2>
 * Every method must return quickly and must not throw for an ordinary delivery failure (mirrors
 * {@code EventPublisherPort}'s own contract) — a disconnected viewer, a full connection registry,
 * or the feature being disabled entirely (a no-op implementation) must never surface as an
 * exception on the caller's own hot path.
 *
 * <h2>Threading</h2>
 * Called from the hot stream-pipeline path ({@code StreamPipeline}, once per completed inference)
 * and from the detection-event debounce engine ({@code DetectionEventEngine}, whenever an event
 * opens, advances, or closes), so implementations must be cheap and effectively fire-and-forget:
 * hand off to a background dispatcher for any real I/O (serializing a payload, writing to a
 * connection) rather than doing it on the calling thread. Safe for concurrent use from many
 * streams at once.
 */
public interface DetectionLiveUpdatePort {

    /**
     * Announces that one stream's inference completed, attributed to the stream's owning asset.
     *
     * @param assetId      the asset that owns the stream this result belongs to
     * @param result       the completed detection result, including an empty one
     * @param worldObjects {@code result}'s world fold (docs/plans/active/CV-ORCHESTRATION-PLAN.md
     *                     §4.6, wave W2.8) — the same objects {@code result.objects()} carries, plus
     *                     the operator/event/render relations this platform owns on top of them.
     *                     Computed once, ahead of this call, by whichever {@code WorldModel} folded
     *                     {@code result} (today: {@code StreamPipeline#onDetectionResult}, right
     *                     after its own live-plane {@code world.accept} call) — a driving adapter
     *                     must treat this as that fold's read-only snapshot for {@code result} and
     *                     must never re-fold or call back into the pipeline to obtain it. Deliberately
     *                     a sibling parameter rather than a new field on {@link DetectionResult}: the
     *                     operator/event/render relations are a platform concern cv-service's own
     *                     wire shape must never carry (see {@link WorldObject}'s own javadoc). Never
     *                     {@code null}; empty exactly when {@code result.objects()} is empty.
     */
    void publishDetections(AssetId assetId, DetectionResult result, List<WorldObject> worldObjects);

    /**
     * Announces a debounced {@link DetectionEvent} occurrence — opened, advanced (a further
     * qualifying observation while already open), or closed — the same occurrence {@code
     * DetectionEventRepositoryPort#save} already persists, announced at the exact same seam
     * rather than duplicated bookkeeping. Carries the event itself (unlike a bare notification)
     * since a driving adapter has no cheaper way to re-derive "which event, in which state" than
     * being told directly, exactly mirroring how {@link #publishDetections} already carries its
     * own payload rather than a bare notification.
     *
     * @param event the event's current state
     */
    void publishDetectionEvent(DetectionEvent event);
}

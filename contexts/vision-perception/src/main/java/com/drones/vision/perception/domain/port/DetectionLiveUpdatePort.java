package com.drones.vision.perception.domain.port;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.perception.domain.model.DetectionEvent;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.TracksSnapshot;

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
     * @param assetId       the asset that owns the stream this result belongs to
     * @param result        the completed detection result, including an empty one
     * @param tracksSnapshot {@code result}'s stream's complete tracks-topic snapshot
     *                      (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.9, wave W9, decision E25)
     *                      — the same assembly {@code GET /api/streams/{id}/tracks} reads, carrying
     *                      {@code result}'s own world fold (§4.6, wave W2.8) alongside tracks,
     *                      tracking stats, pipeline latency, detection rate, detection state and
     *                      follow status. Computed once, ahead of this call, by whichever pipeline
     *                      folded {@code result} (today: {@code StreamPipeline#onDetectionResult},
     *                      right after its own live-plane updates) — a driving adapter must treat
     *                      this as that pipeline's read-only snapshot for {@code result} and must
     *                      never re-fold or call back into the pipeline to obtain it. Widened from a
     *                      bare {@code List<WorldObject>} in wave W9 (CLAUDE.md rule 10: a new
     *                      collaborator means updating every call site, never an overload) because
     *                      the {@code tracks:} SSE topic used to publish only the world fold while a
     *                      per-stream poll re-derived everything else the controller reports — this
     *                      parameter is that whole snapshot instead, so the poll becomes the
     *                      fallback it should always have been. Never {@code null}.
     */
    void publishDetections(AssetId assetId, DetectionResult result, TracksSnapshot tracksSnapshot);

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

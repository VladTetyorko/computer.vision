package com.drones.vision.perception.domain.port;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.StreamId;

/**
 * Driven port: whether anything is currently consuming a stream's detection output
 * (docs/plans/active/CV-DEMAND-PLAN.md &sect;2-3.1) &mdash; the system-derived half of the two
 * independent gates {@code StreamPipeline} ANDs together before it runs inference at all (the
 * other half, operator intent, is {@code PipelineConfig#detectionEnabled()}). Deliberately
 * protocol-agnostic: a live SSE subscription to a stream's detections and a recent poll of {@code
 * GET /api/streams/{id}/detections} are both legitimate demand, and this port collapses whichever
 * one is true into a single fact so nothing above it needs to know which driving protocol asked.
 *
 * <h2>Contract</h2>
 * Cheap and non-blocking &mdash; an in-memory read at most, never network I/O. Must not throw:
 * the caller ({@code DefaultStreamService}'s demand-poll task) wraps every call in its own {@code
 * catch (Throwable)} regardless, since a periodically-scheduled task must never let one failing
 * evaluation silently stop every later one, but an implementation that answers {@code false}
 * instead of throwing keeps that safety net reserved for genuine bugs rather than routine control
 * flow.
 *
 * <h2>Threading</h2>
 * Called from one dedicated scheduler thread, on a fixed poll interval ({@code
 * StreamPipelineSettings#detectionDemandPollInterval()}) &mdash; never from the video or detection
 * hot path. An implementation must be safe to call concurrently for different streams, even though
 * today's one caller evaluates them sequentially, one stream per poll tick.
 */
public interface DetectionDemandPort {

    /**
     * Whether anything is currently consuming this stream's detections.
     *
     * @param streamId the stream being evaluated
     * @param assetId  the stream's owning asset, or {@code null} for a device-only stream with no
     *                 resolved asset (no {@code UsageTracker} configured, or the device has no
     *                 owning asset) &mdash; an implementation that only recognizes asset-scoped
     *                 demand (e.g. an SSE topic keyed by asset id) simply has nothing to check in
     *                 that case and may answer {@code false}
     * @return {@code true} if detection output for this stream is currently wanted
     */
    boolean detectionWanted(StreamId streamId, AssetId assetId);
}

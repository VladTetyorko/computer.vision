package com.drones.vision.perception.domain.port;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.StreamId;

/**
 * Driven port: whether anything is currently consuming a stream's <b>trace</b> — the warm trace
 * tier {@code PipelineConfig#trace()} requests of cv-service (docs/plans/active/
 * CV-ORCHESTRATION-PLAN.md &sect;4.4) — as opposed to {@link DetectionDemandPort}, which answers
 * the same question for a stream's ordinary detection output. The two are deliberately separate
 * ports rather than one wider signature: a viewer watching a stream's boxes is not, by itself,
 * asking for the extra diagnostic cost of a trace, and a debug console open on {@code GET
 * /api/streams/{id}/cv/trace} (or subscribed to its SSE topic) is not, by itself, ordinary
 * detection demand — the two consumers are usually different people asking different questions,
 * and the trace tier's own cost (docs/plans/active/cv-orchestration/R2-backend-control-plane.md
 * &sect;2) means over-answering "yes" here is not free the way it is for {@link
 * DetectionDemandPort} answering "keep detecting".
 *
 * <h2>Contract</h2>
 * Cheap and non-blocking &mdash; an in-memory read at most, never network I/O. Must not throw:
 * {@code DefaultStreamService}'s demand-poll task wraps every call in its own {@code catch
 * (Throwable)} regardless (see {@link DetectionDemandPort}'s own contract for the identical
 * reasoning), but an implementation that answers {@code false} instead of throwing keeps that
 * safety net reserved for genuine bugs rather than routine control flow.
 *
 * <h2>Fail-open, structurally</h2>
 * There is no {@code TraceDemandPort} implementation in this wave (docs/plans/active/
 * CV-ORCHESTRATION-PLAN.md wave W2 — the concrete adapter, backed by {@code cv-trace:<assetId>}
 * SSE subscriber counts and {@code GET .../cv/trace} poll timestamps, is wave W2's own later
 * station/vision-api step): {@code DefaultStreamService} treats an absent port exactly like an
 * absent {@link DetectionDemandPort} — the demand-poll evaluation for trace is simply never run,
 * so {@code PipelineConfig#trace()} stays at whatever value the stream started with (today always
 * {@code false} — no profile ever requests it). This is "fail open" in the same sense every other
 * optional collaborator in this package is: an absent one changes nothing about existing behavior.
 * It is deliberately <b>not</b> a copy of {@link DetectionDemandPort}'s bootstrapped-{@code true}
 * field default — that default is safe there only because it collapses back to detection's own
 * pre-demand-gating behavior (detect unconditionally); trace's pre-existing behavior is always
 * {@code false}, so defaulting it {@code true} instead would be a new, costly behavior, not a
 * safety net. See {@code StreamPipeline#updateTraceDemand}'s own javadoc for where this is enforced.
 *
 * <h2>Threading</h2>
 * Called from the same demand-poll scheduler thread {@link DetectionDemandPort} is, on the same
 * fixed poll interval; never from the video or detection hot path.
 */
public interface TraceDemandPort {

    /**
     * Whether anything is currently consuming this stream's trace.
     *
     * @param streamId the stream being evaluated
     * @param assetId  the stream's owning asset, or {@code null} for a device-only stream with no
     *                 resolved asset &mdash; an implementation that only recognizes asset-scoped
     *                 demand (e.g. an SSE topic keyed by asset id) simply has nothing to check in
     *                 that case and may answer {@code false}
     * @return {@code true} if a trace of this stream is currently wanted
     */
    boolean traceWanted(StreamId streamId, AssetId assetId);
}

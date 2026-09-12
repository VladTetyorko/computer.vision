package com.drones.vision.perception.application.pipeline;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Rolling record of every {@link GateDecision} {@link StreamPipeline#maybeDetect} makes, rendered
 * for the {@code cv-trace} debug surface (docs/plans/active/CV-ORCHESTRATION-PLAN.md &sect;4.4,
 * docs/plans/active/cv-orchestration/R2-backend-control-plane.md &sect;2). A peer of {@link
 * DetectionRateWindow} rather than part of it, for the same reason {@link WorldModel} and {@link
 * TrackingStatsWindow} are peers: this is a distinct read model (a trace of individual decisions)
 * over the same underlying events {@link DetectionRateWindow} aggregates into counters.
 *
 * <h2>Coalescing, not one entry per frame</h2>
 * {@link GateDecision}'s own javadoc is explicit: "not one entry per frame — a ten-second outage
 * backoff produces one entry per deadline it skips, not one per arriving frame." {@link
 * StreamPipeline#maybeDetect} is called once per arriving video frame, far more often than once
 * per sampler deadline for any stream whose {@code inferenceFps} is below its video frame rate —
 * so a literal one-{@link #record}-call-per-{@code SKIPPED} classification would spend this ring's
 * entire bounded {@link #depth} on a single steady state (most visibly {@link
 * GateReason#DEADLINE_NOT_DUE}, which by definition holds on every frame between two deadlines)
 * within a few seconds, evicting exactly the history a trace exists to show.
 *
 * <p>This class resolves that by coalescing: a new {@link GateDecision} whose {@link
 * GateDecision#outcome()} is {@link GateOutcome#SKIPPED} and whose {@link GateDecision#reason()}
 * matches the most recently recorded entry's reason <b>replaces</b> that entry (refreshing its
 * timestamp/frame-sequence/demand snapshot) rather than appending a new one — a run of identical
 * skip reasons therefore occupies exactly one ring slot, current as of the latest frame that
 * observed it, and the ring's {@link #depth} is spent on genuine <em>transitions</em> instead of
 * repetition. {@link GateOutcome#SENT} and {@link GateOutcome#PROBE} are never coalesced — each is
 * a distinct round of real work submitted to cv-service, and a trace that merged two separate
 * submissions into one entry would misreport how many frames were actually sent.
 *
 * <h2>Threading</h2>
 * Every method is {@code synchronized}: {@link #record} runs on whichever thread evaluates the
 * gate (the video/{@code onNext} thread for push mode, {@link StreamPipeline#submitDetection}'s
 * caller for the {@link GateOutcome#SENT}/{@link GateOutcome#PROBE}/{@link
 * GateReason#CV_UNAVAILABLE} classification), while {@link #recent}/{@link #all} are read from an
 * HTTP thread serving {@code GET /api/streams/{id}/cv/trace} (a later wave step).
 */
final class FrameGateLedger {

    private final int depth;
    private final Deque<GateDecision> entries;

    /**
     * @param depth how many entries this ring retains after coalescing; must be positive. {@link
     *              StreamPipeline} passes {@link StreamPipelineSettings#gateLedgerDepth()} — a
     *              deployment-configured value, never a literal here (no magic numbers).
     */
    FrameGateLedger(int depth) {
        if (depth <= 0) {
            throw new IllegalArgumentException("FrameGateLedger depth must be positive: " + depth);
        }
        this.depth = depth;
        this.entries = new ArrayDeque<>(depth);
    }

    /**
     * Records one gate decision, coalescing it into the most recent entry when both are {@link
     * GateOutcome#SKIPPED} for the same {@link GateReason} (see class javadoc), and evicting the
     * oldest entry first whenever a genuinely new entry would exceed {@link #depth}.
     */
    synchronized void record(GateDecision decision) {
        Objects.requireNonNull(decision, "decision must not be null");
        GateDecision last = entries.peekLast();
        boolean coalesce = last != null
                && decision.outcome() == GateOutcome.SKIPPED
                && last.outcome() == GateOutcome.SKIPPED
                && decision.reason() == last.reason();
        if (coalesce) {
            entries.removeLast();
        } else if (entries.size() == depth) {
            entries.removeFirst();
        }
        entries.addLast(decision);
    }

    /**
     * @param last how many of the most recent entries to return; must not be negative. Fewer than
     *             {@code last} are returned when the ledger holds fewer entries than that.
     * @return the most recent {@code last} entries, oldest first — never {@code null}
     */
    synchronized List<GateDecision> recent(int last) {
        if (last < 0) {
            throw new IllegalArgumentException("last must not be negative: " + last);
        }
        List<GateDecision> all = List.copyOf(entries);
        int from = Math.max(0, all.size() - last);
        return all.subList(from, all.size());
    }

    /** @return every entry currently held, oldest first — never {@code null} */
    synchronized List<GateDecision> all() {
        return List.copyOf(entries);
    }

    /** Empties the ring; called by {@link StreamPipeline#clearDetectionDerivedState()}. */
    synchronized void clear() {
        entries.clear();
    }
}

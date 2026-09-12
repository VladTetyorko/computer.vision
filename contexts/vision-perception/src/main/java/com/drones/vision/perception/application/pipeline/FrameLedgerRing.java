package com.drones.vision.perception.application.pipeline;

import com.drones.vision.perception.domain.model.FrameLedger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Rolling history of the most recent {@link FrameLedger}s this pipeline actually received from
 * cv-service, for the {@code cv-trace} debug surface's "frame ledger" half (docs/plans/active/
 * CV-ORCHESTRATION-PLAN.md &sect;4.4: {@code GET /api/streams/{id}/cv/trace?last=N} answers "gate +
 * frame + world ledgers" — this class is the frame half, {@link FrameGateLedger} the gate half,
 * {@link WorldModel#objects()} the world half).
 *
 * <p><b>No coalescing</b>, unlike {@link FrameGateLedger}: a {@link FrameLedger} is only ever
 * appended when cv-service actually attached one to a response ({@link
 * com.drones.vision.perception.domain.model.DetectionResult#ledger()} present, which itself only
 * happens while {@link com.drones.vision.perception.domain.model.PipelineConfig#trace()} is {@code
 * true}), never once per video frame the way {@link StreamPipeline#maybeDetect} runs — there is no
 * steady-state repetition here for coalescing to guard against, and every entry is a genuinely
 * distinct frame's worth of contributor evidence worth keeping on its own.
 *
 * <h2>Threading</h2>
 * Every method is {@code synchronized}: {@link #record} runs on whichever thread completes an
 * inference (mirroring {@link WorldModel#accept}), while {@link #recent}/{@link #all} are read from
 * an HTTP thread serving {@code GET /api/streams/{id}/cv/trace}.
 */
final class FrameLedgerRing {

    private final int depth;
    private final Deque<FrameLedger> entries;

    /**
     * @param depth how many entries this ring retains; must be positive. {@link StreamPipeline}
     *              passes {@link StreamPipelineSettings#frameLedgerDepth()} — a deployment-configured
     *              value, never a literal here (no magic numbers).
     */
    FrameLedgerRing(int depth) {
        if (depth <= 0) {
            throw new IllegalArgumentException("FrameLedgerRing depth must be positive: " + depth);
        }
        this.depth = depth;
        this.entries = new ArrayDeque<>(depth);
    }

    /** Appends one entry, evicting the oldest first whenever this would exceed {@link #depth}. */
    synchronized void record(FrameLedger ledger) {
        Objects.requireNonNull(ledger, "ledger must not be null");
        if (entries.size() == depth) {
            entries.removeFirst();
        }
        entries.addLast(ledger);
    }

    /**
     * @param last how many of the most recent entries to return; must not be negative. Fewer than
     *             {@code last} are returned when the ring holds fewer entries than that.
     * @return the most recent {@code last} entries, oldest first — never {@code null}
     */
    synchronized List<FrameLedger> recent(int last) {
        if (last < 0) {
            throw new IllegalArgumentException("last must not be negative: " + last);
        }
        List<FrameLedger> all = List.copyOf(entries);
        int from = Math.max(0, all.size() - last);
        return all.subList(from, all.size());
    }

    /** @return every entry currently held, oldest first — never {@code null} */
    synchronized List<FrameLedger> all() {
        return List.copyOf(entries);
    }

    /** Empties the ring; called by {@link StreamPipeline#clearDetectionDerivedState()}. */
    synchronized void clear() {
        entries.clear();
    }
}

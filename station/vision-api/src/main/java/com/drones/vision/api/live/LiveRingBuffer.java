package com.drones.vision.api.live;

import com.drones.vision.api.dto.LiveEnvelopeResponse;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * A bounded, sequence-numbered, per-topic backlog of {@link LiveEnvelopeResponse}s
 * (docs/plans/done/REALTIME-PLAN.md §4, item 1) — backs both {@code Last-Event-ID} resume and, for a topic
 * with nothing better to offer, a fresh connection's "snapshot" (see {@link
 * LiveUpdateRegistry}'s own javadoc for the one topic, {@code fleet}, that has an actual live
 * query to fall back on instead).
 *
 * <h2>Two retention modes</h2>
 * <ul>
 *   <li><b>FIFO</b> ({@code collapseToLatest=false}, {@code telemetry}/{@code event} topics):
 *       every appended envelope is retained up to {@code capacity}, oldest evicted first — each
 *       one is individually meaningful (an appended telemetry sample, a raised domain event), so
 *       none may be silently dropped in favor of a later one.</li>
 *   <li><b>Latest-only</b> ({@code collapseToLatest=true}, {@code fleet}/{@code detections}
 *       topics): appending replaces the single retained entry outright — an older fleet snapshot
 *       or detection result has no value once a newer one has landed (docs/plans/done/REALTIME-PLAN.md §4,
 *       item 3: "detections emit latest-frame-only").</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Every method is {@code synchronized} on this instance — appends (from the coalescing flush or a
 * direct publish) and reads (from a connecting/resuming viewer) can race freely.
 */
final class LiveRingBuffer {

    private final int capacity;
    private final boolean collapseToLatest;
    private final Deque<LiveEnvelopeResponse> entries = new ArrayDeque<>();

    /**
     * Set the first time this buffer ever drops something (a FIFO eviction, or a collapse-to-
     * latest replacement of a real prior entry) — see {@link #canResumeFrom(long)} for why this
     * matters more than comparing directly against the oldest retained entry's {@code seq}: with a
     * single {@code seq} counter shared across every topic (see {@code LiveEnvelopeResponse}'s
     * javadoc), a buffer's own oldest retained entry can legitimately have a much higher {@code
     * seq} than a caller's {@code sinceSeq} simply because <em>other topics</em> were busy in
     * between — not because this topic ever lost anything.
     */
    private boolean everDropped = false;

    LiveRingBuffer(int capacity, boolean collapseToLatest) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.collapseToLatest = collapseToLatest;
    }

    /**
     * Appends one envelope, evicting the oldest if over capacity ({@code collapseToLatest=false}),
     * or replacing the single retained entry outright ({@code collapseToLatest=true}).
     *
     * @param envelope the envelope to append; expected (not enforced) to have a strictly higher
     *                  {@code seq} than anything already retained
     */
    synchronized void append(LiveEnvelopeResponse envelope) {
        if (collapseToLatest) {
            if (!entries.isEmpty()) {
                everDropped = true;
            }
            entries.clear();
        }
        entries.addLast(envelope);
        while (entries.size() > capacity) {
            entries.removeFirst();
            everDropped = true;
        }
    }

    /**
     * @return {@code true} if nothing has ever been appended (or everything was since evicted, which
     *         cannot happen with {@code capacity >= 1} unless nothing was ever appended)
     */
    synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * @return every currently retained envelope, oldest first
     */
    synchronized List<LiveEnvelopeResponse> snapshot() {
        return List.copyOf(entries);
    }

    /**
     * @param sinceSeq a sequence number a caller claims to already have everything up to and
     *                 including
     * @return every retained envelope with {@code seq() > sinceSeq}, oldest first; empty if none
     *         qualify (including if nothing is retained at all)
     */
    synchronized List<LiveEnvelopeResponse> since(long sinceSeq) {
        return entries.stream().filter(e -> e.seq() > sinceSeq).toList();
    }

    /**
     * @param sinceSeq a sequence number a caller claims to already have everything up to and
     *                 including
     * @return {@code true} if resuming from {@code sinceSeq} via {@link #since(long)} would not
     *         silently skip anything this buffer ever actually dropped. Always {@code true} if
     *         this buffer has never dropped anything (a never-evicted/never-replaced buffer's
     *         {@link #since(long)} is correct for <em>any</em> {@code sinceSeq}, however old — see
     *         {@link #everDropped}'s own javadoc for why that's a better signal than comparing
     *         directly against the oldest retained entry's {@code seq}); once something has been
     *         dropped, {@code true} only if {@code sinceSeq} reaches at least one seq behind the
     *         oldest still-retained entry.
     */
    synchronized boolean canResumeFrom(long sinceSeq) {
        return !everDropped || entries.isEmpty() || sinceSeq >= entries.peekFirst().seq() - 1;
    }

    /**
     * Whether this buffer has ever dropped a retained envelope — {@code live-updates}'s {@code
     * SubsystemStatusPort} plumbing (docs/plans/active/SYSTEM-STATUS-PLAN.md §4.2) surfaces this as
     * an informational signal (a busy, healthy buffer drops routinely once past capacity; it is not
     * itself a fault) rather than reading anything into it beyond "this deployment has seen traffic".
     */
    synchronized boolean everDropped() {
        return everDropped;
    }
}

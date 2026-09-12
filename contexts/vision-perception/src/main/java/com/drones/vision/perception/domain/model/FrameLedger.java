package com.drones.vision.perception.domain.model;

import com.drones.vision.kernel.StreamId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One frame's complete evidence (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.4) — every
 * contributor that ran or was skipped, and every claim made about every object, for one sampled
 * frame.
 *
 * <p>This is the <strong>warm</strong> debug tier: present only on a frame cv-service was asked
 * to trace ({@link PipelineConfig#trace()}), never persisted, never on the durable path — a
 * client asks for it (an opt-in inspector) or it is forgotten.
 *
 * <p>{@code detectorReason} carries the wire's {@code DetectorReason} enum value <strong>as its
 * own name, a plain string</strong> — deliberately <em>not</em> the domain {@link DetectorReason}
 * enum — because the ledger must survive a cv-service that reports a reason this Java build does
 * not yet know about; a plain string can never fail to decode, an enum could.
 *
 * @param streamId       stream the source frame belongs to; must not be {@code null}
 * @param sequence       this session's own frame counter, from 0; must not be negative
 * @param capturedAt     capture timestamp of the source frame; must not be {@code null}
 * @param levelServed    the capability ladder level this frame actually ran at; must not be
 *                       negative
 * @param detectorReason the wire's {@code DetectorReason} enum value name; must not be {@code
 *                       null}, empty allowed (see above)
 * @param eligible       contributor families the budget allowed this frame; defensively copied,
 *                       never {@code null}
 * @param entries        one row per contributor that ran or was considered, in run order;
 *                       defensively copied, never {@code null}
 * @param objects        track id to every claim made about it this frame; defensively copied —
 *                       both the map and each value list are immutable — never {@code null}
 * @param dropsSinceLast frames the mailbox dropped before this one (push mode); must not be
 *                       negative
 * @param gateWaitMillis wall time inside the detector: queueing plus inference; must be finite
 *                       and non-negative
 * @param totalMillis    the frame's total cost; must be finite and non-negative
 * @param halted         whether a contributor stopped the frame (e.g. no model resolved)
 */
public record FrameLedger(StreamId streamId, long sequence, Instant capturedAt, int levelServed,
                           String detectorReason, List<String> eligible, List<LedgerEntry> entries,
                           Map<Long, List<ObjectEvidence>> objects, int dropsSinceLast, double gateWaitMillis,
                           double totalMillis, boolean halted) {

    public FrameLedger {
        if (streamId == null) {
            throw new IllegalArgumentException("FrameLedger streamId must not be null");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("FrameLedger sequence must not be negative: " + sequence);
        }
        if (capturedAt == null) {
            throw new IllegalArgumentException("FrameLedger capturedAt must not be null");
        }
        if (levelServed < 0) {
            throw new IllegalArgumentException("FrameLedger levelServed must not be negative: " + levelServed);
        }
        if (detectorReason == null) {
            throw new IllegalArgumentException("FrameLedger detectorReason must not be null");
        }
        if (eligible == null) {
            throw new IllegalArgumentException("FrameLedger eligible must not be null");
        }
        if (entries == null) {
            throw new IllegalArgumentException("FrameLedger entries must not be null");
        }
        if (objects == null) {
            throw new IllegalArgumentException("FrameLedger objects must not be null");
        }
        if (dropsSinceLast < 0) {
            throw new IllegalArgumentException("FrameLedger dropsSinceLast must not be negative: " + dropsSinceLast);
        }
        if (!Double.isFinite(gateWaitMillis) || gateWaitMillis < 0) {
            throw new IllegalArgumentException(
                    "FrameLedger gateWaitMillis must be finite and non-negative: " + gateWaitMillis);
        }
        if (!Double.isFinite(totalMillis) || totalMillis < 0) {
            throw new IllegalArgumentException("FrameLedger totalMillis must be finite and non-negative: " + totalMillis);
        }
        eligible = List.copyOf(eligible);
        entries = List.copyOf(entries);
        Map<Long, List<ObjectEvidence>> copied = new LinkedHashMap<>();
        for (Map.Entry<Long, List<ObjectEvidence>> entry : objects.entrySet()) {
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("FrameLedger objects must not contain a null evidence list");
            }
            copied.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        objects = Map.copyOf(copied);
    }
}

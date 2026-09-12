package com.drones.vision.perception.domain.model;

import java.util.Map;

/**
 * One contributor's row on one frame (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.4).
 *
 * <p>{@code costMillis} is the contributor's <strong>own</strong> measurement when it has one
 * that reaches the wire (the detector's {@code inference_millis}, ego-motion's {@code
 * motion_millis}) and the orchestrator's wall clock otherwise — the two are never summed into a
 * single "total"; that total lives on {@link FrameLedger#totalMillis()} instead.
 *
 * @param contributorId who ran — e.g. {@code "detect.full"}, {@code "assoc.cost"}, {@code
 *                       "follow.lk"}; must not be blank
 * @param outcome        what happened to this contributor this frame; must not be {@code null}
 * @param reason         why it was skipped, or {@code "<ExcType>: <message>"} when it failed;
 *                       must not be {@code null} — empty string means "no reason given," never
 *                       {@code null}
 * @param costMillis     this contributor's own cost for the frame; must be finite and
 *                       non-negative
 * @param summary        free-form facts this contributor chose to record; defensively copied to
 *                       an immutable map, never {@code null}
 */
public record LedgerEntry(String contributorId, LedgerOutcome outcome, String reason, double costMillis,
                           Map<String, String> summary) {

    public LedgerEntry {
        if (contributorId == null || contributorId.isBlank()) {
            throw new IllegalArgumentException("LedgerEntry contributorId must not be blank");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("LedgerEntry outcome must not be null");
        }
        if (reason == null) {
            throw new IllegalArgumentException("LedgerEntry reason must not be null");
        }
        if (!Double.isFinite(costMillis) || costMillis < 0) {
            throw new IllegalArgumentException("LedgerEntry costMillis must be finite and non-negative: " + costMillis);
        }
        if (summary == null) {
            throw new IllegalArgumentException("LedgerEntry summary must not be null");
        }
        summary = Map.copyOf(summary);
    }
}

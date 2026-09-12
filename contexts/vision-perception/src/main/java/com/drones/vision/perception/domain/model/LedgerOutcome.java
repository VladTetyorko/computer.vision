package com.drones.vision.perception.domain.model;

/**
 * What happened to one contributor on one frame (docs/plans/active/CV-ORCHESTRATION-PLAN.md
 * §4.4) — one value per {@link LedgerEntry}.
 *
 * <p>Mirrors {@code cv.proto}'s {@code LedgerOutcome} minus {@code
 * LEDGER_OUTCOME_UNSPECIFIED} — the same posture {@link DetectorReason} already takes toward
 * {@code DetectorReason}'s own proto zero-value; mapping that sentinel is an adapter-boundary
 * concern, not this enum's.
 */
public enum LedgerOutcome {
    /** It ran and produced whatever it produces. */
    RAN,
    /** The budget refused it, or it declined itself — {@link LedgerEntry#reason()} says why. */
    SKIPPED,
    /** It raised; the frame continued without its output. */
    FAILED
}

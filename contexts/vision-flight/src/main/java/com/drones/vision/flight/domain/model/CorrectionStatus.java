package com.drones.vision.flight.domain.model;

/**
 * How much a {@link TrackCorrection} is believed, after both halves of D5's two-owner gate
 * (docs/plans/active/VISUAL-GEO-V2-PLAN.md §3.5/§4.3) — Python's "is this frame confidently matched"
 * and Java's "do I believe the aircraft is there". Frozen wire spelling: the enum name is the wire
 * string (§3.4/§3.5).
 */
public enum CorrectionStatus {
    /** Cleared every gate: radius floor and ceiling, consecutive agreement, and Python's own
     *  sequence-converged + cell-calibrated evidence. The only status that may arm/clear the
     *  divergence alarm (§4.5). */
    CONFIRMED,
    /** A believed position that has not (yet, or ever) cleared every {@link #CONFIRMED} gate —
     *  above the radius ceiling, not yet agreed by enough consecutive fixes, or Python's own
     *  evidence falls short. Neither arms nor clears the divergence alarm. */
    PROBABLE,
    /** No position at all — refused by a Python-side gate, or by Java's own radius floor. {@link
     *  TrackCorrection#position()} is {@code null} and {@link TrackCorrection#refusal()} is
     *  non-empty. */
    NO_FIX
}

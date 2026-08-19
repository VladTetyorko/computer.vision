package com.drones.vision.flight.domain.model;

/**
 * Which correction technique produced a {@link TrackCorrection}. One value today, by design
 * (docs/plans/active/VISUAL-GEO-V2-PLAN.md §3.5) — the LIGHT onboard tier and any future correction
 * source are separate cycles, not a reason to speculatively widen this enum now.
 */
public enum CorrectionSource {
    /** cv-service's HEAVY-A pipeline: rectify -&gt; retrieve -&gt; re-rank -&gt; sequence fuse -&gt; pose. */
    VISUAL_HEAVY
}

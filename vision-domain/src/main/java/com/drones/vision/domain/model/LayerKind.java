package com.drones.vision.domain.model;

/**
 * What kind of {@link MapLayer} this is (docs/plans/done/MAP-REWORK-PLAN.md §2.1) — drives default visibility
 * and who may write to it before any explicit {@link LayerGrant} is consulted; see {@code
 * MapAccessPolicy} (vision-application) for the exact resolution rules.
 */
public enum LayerKind {

    /** The single system-wide Common Operational Picture layer: org-wide visible, MANAGER+
     *  writes, the default promotion target for verified marks. Exactly one per deployment. */
    COP,

    /** Owned by a group — group members see and contribute; grants extend further. */
    TEAM,

    /** Owner-only by default; grants extend visibility/write access to specific subjects. */
    PERSONAL
}

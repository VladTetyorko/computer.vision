package com.drones.vision.perception.domain.model;

/**
 * What tier a viewer should render one object at (docs/plans/active/CV-ORCHESTRATION-PLAN.md
 * §4.6) — the platform's own answer to "why isn't this box drawn."
 *
 * <p>{@link #T0}/{@link #T1}/{@link #T2}/{@link #T3} are the <strong>frozen client
 * vocabulary</strong> — {@code station/vision-web/src/app/shared/player/detection-overlay-logic.ts}'s
 * {@code DetectionTier} — kept byte-identical so the server can take over tier assignment without
 * a translation layer at the client boundary. {@link #HIDDEN} is server-only and has no client
 * counterpart: it means the object exists in the world model but is suppressed from every viewer
 * read model (today: its label is deny-filtered).
 */
public enum RenderTier {
    /** Suppressed from every viewer read model; exists in the world model only. */
    HIDDEN,
    T0,
    T1,
    T2,
    T3
}

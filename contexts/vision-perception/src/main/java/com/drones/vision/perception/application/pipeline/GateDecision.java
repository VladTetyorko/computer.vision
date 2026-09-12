package com.drones.vision.perception.application.pipeline;

import java.time.Instant;

/**
 * One sampler <strong>deadline</strong>'s worth of gating (docs/plans/active/cv-orchestration/
 * R2-backend-control-plane.md §2) — not one entry per frame: a ten-second outage backoff produces
 * one entry per deadline it skips, not one per arriving frame.
 *
 * @param frameSequence the frame sequence this deadline evaluated against; must not be negative
 * @param at            when this decision was made; must not be {@code null}
 * @param outcome       what happened; must not be {@code null}
 * @param reason        why the frame was skipped; must be {@code null} if and only if
 *                       {@code outcome} is not {@link GateOutcome#SKIPPED} — non-{@code null} iff
 *                       skipped, exactly the pairing {@link
 *                       com.drones.vision.perception.domain.model.TrackingTelemetry} enforces
 *                       between {@code detectorRan} and {@code reason}
 * @param demand        the demand facts this module owns at the moment of this decision; must
 *                       not be {@code null}
 */
public record GateDecision(long frameSequence, Instant at, GateOutcome outcome, GateReason reason,
                            DemandSnapshot demand) {

    public GateDecision {
        if (frameSequence < 0) {
            throw new IllegalArgumentException("GateDecision frameSequence must not be negative: " + frameSequence);
        }
        if (at == null) {
            throw new IllegalArgumentException("GateDecision at must not be null");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("GateDecision outcome must not be null");
        }
        if (demand == null) {
            throw new IllegalArgumentException("GateDecision demand must not be null");
        }
        if (outcome == GateOutcome.SKIPPED && reason == null) {
            throw new IllegalArgumentException("GateDecision reason must not be null when outcome is SKIPPED");
        }
        if (outcome != GateOutcome.SKIPPED && reason != null) {
            throw new IllegalArgumentException("GateDecision reason must be null when outcome is not SKIPPED");
        }
    }
}

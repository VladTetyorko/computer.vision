package com.drones.vision.warehouse.application.discovery;

import com.drones.vision.warehouse.domain.model.DiscoveryCandidate;

/**
 * The result of one {@link DiscoveryInboxService#report} call (docs/plans/active/
 * SOURCE-ONBOARDING-2-PLAN.md &sect;3.2 C4) — lets a caller (the periodic sweep runner, outside this
 * module) publish a live-update delta only when something an operator would care about actually
 * happened, instead of on every sweep regardless of content.
 *
 * @param candidate the upserted candidate, as saved
 * @param changed   {@code true} when this report is a first sighting of the identity, or when
 *                  {@link DiscoveryCandidate#status()} or {@link DiscoveryCandidate#discovered()}
 *                  differ from the candidate's pre-report value; {@code false} for a routine
 *                  re-report that only refreshed {@link DiscoveryCandidate#lastSeen()}
 */
public record ReportOutcome(DiscoveryCandidate candidate, boolean changed) {

    public ReportOutcome {
        if (candidate == null) {
            throw new IllegalArgumentException("ReportOutcome candidate must not be null");
        }
    }
}

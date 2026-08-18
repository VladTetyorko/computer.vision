package com.drones.vision.api.dto;

import java.util.List;

/**
 * Request body for {@code POST /api/assets/{assetId}/remediate} (docs/plans/active/
 * DRONE-ONBOARDING-PLAN.md §8.1, frozen): {@code { "features": [...], "actions": [...] } }.
 *
 * <p>{@code features} names which {@link com.drones.vision.flight.domain.model.FeatureReadiness}
 * rows to attempt remediating; {@code actions} names which {@link
 * com.drones.vision.flight.domain.model.RemedyKind} mechanisms the caller is willing to have
 * attempted (a feature whose own required remedy is not in this list is reported {@code
 * UNSUPPORTED}, never silently skipped — see {@code RemediationOrchestrator}).
 *
 * @param features requested feature keys; {@code null} treated as empty
 * @param actions  requested remedy-kind names (e.g. {@code "MESSAGE_INTERVAL"}); {@code null}
 *                 treated as empty
 */
public record RemediationRequest(List<String> features, List<String> actions) {

    public List<String> featuresOrEmpty() {
        return features == null ? List.of() : features;
    }

    public List<String> actionsOrEmpty() {
        return actions == null ? List.of() : actions;
    }
}

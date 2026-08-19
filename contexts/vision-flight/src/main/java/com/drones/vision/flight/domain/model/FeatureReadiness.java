package com.drones.vision.flight.domain.model;

/**
 * One row of a {@link ReadinessReport} — the evaluation of a single frozen feature key
 * (docs/plans/active/DRONE-ONBOARDING-PLAN.md §8.1) against one {@link VehicleProfile}.
 *
 * @param featureKey one of {@link FeatureRequirement#FEATURE_KEYS}
 * @param label      human-readable label
 * @param status     this feature's evaluated status
 * @param detail     the honest sentence explaining {@code status} (C7 — absent evidence says why);
 *                   not blank
 * @param remedy     the remedy this row recommends, or {@code null} when none applies (a {@link
 *                   FeatureStatus#READY} row, or one with nothing automatable)
 */
public record FeatureReadiness(String featureKey, String label, FeatureStatus status, String detail, RemedyKind remedy) {

    public FeatureReadiness {
        if (featureKey == null || !FeatureRequirement.FEATURE_KEYS.contains(featureKey)) {
            throw new IllegalArgumentException(
                    "featureKey must be one of the frozen v1 keys " + FeatureRequirement.FEATURE_KEYS + ": " + featureKey);
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("FeatureReadiness label must not be blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("FeatureReadiness status must not be null");
        }
        if (detail == null || detail.isBlank()) {
            throw new IllegalArgumentException("FeatureReadiness detail must not be blank");
        }
    }
}

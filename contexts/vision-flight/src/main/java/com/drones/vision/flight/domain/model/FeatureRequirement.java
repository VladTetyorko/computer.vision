package com.drones.vision.flight.domain.model;

import java.util.Set;

/**
 * One seeded row of the feature-requirement table (docs/plans/active/DRONE-ONBOARDING-PLAN.md §5.1,
 * D6) — "feature × required message + minimum Hz × required parameter", the {@code DeviceType}-enum
 * -to-{@code DeviceCategory}-data move applied a second time. Adding a feature requirement is meant
 * to be a migration (a new row), never a five-layer code edit — this module only defines the row's
 * shape and reads it through {@link com.drones.vision.flight.domain.port.FeatureRequirementRepositoryPort};
 * seeding the actual rows is storage's job (O5).
 *
 * <p>A firmware with no row at all for a given {@code featureKey} is not an error — {@link
 * com.drones.vision.flight.application.ReadinessService} reports that feature {@code UNKNOWN} for
 * that firmware, "a correct answer, not a failure" (§3.1 NEGOTIATE).
 *
 * <p>Both evidence fields ({@code requiredMessageId}/{@code minimumHz} and {@code
 * requiredParameterName}) are independently nullable: a row may require a message rate, a parameter,
 * both, or neither. A row with neither declares "this firmware supports the feature at all" without
 * a concrete telemetry check this module can perform (e.g. {@code command-tx}, whose readiness turns
 * on the vehicle's own capability bitmask, not a message or parameter) — {@link ReadinessService}
 * treats such a row as trivially satisfied once it exists.
 *
 * @param featureKey            one of {@link #FEATURE_KEYS} — the frozen v1 primary key
 *                             (docs/plans/active/DRONE-ONBOARDING-PLAN.md §8.1); not renamed here,
 *                             flagged instead if ever believed wrong
 * @param label                 human-readable label for this feature
 * @param firmware              the firmware this row applies to (e.g. {@code "ardupilot"}); not blank
 * @param requiredMessageId     the MAVLink message id this feature needs arriving, or {@code null}
 * @param requiredMessageName   the message's human name, for detail text; required (non-blank) iff
 *                             {@code requiredMessageId} is present
 * @param minimumHz             the minimum acceptable rate for that message; required (non-negative)
 *                             iff {@code requiredMessageId} is present
 * @param requiredParameterName a parameter that must be readable/present for this feature, or
 *                             {@code null}
 */
public record FeatureRequirement(
        String featureKey,
        String label,
        String firmware,
        Integer requiredMessageId,
        String requiredMessageName,
        Double minimumHz,
        String requiredParameterName) {

    /**
     * The frozen v1 feature keys (docs/plans/active/DRONE-ONBOARDING-PLAN.md §8.1) — exact strings,
     * the requirement table's primary key. Enforced here, not just documented, so a typo'd or
     * not-yet-frozen key fails fast at construction rather than silently becoming an eleventh
     * "supported" feature nobody agreed to.
     */
    public static final Set<String> FEATURE_KEYS = Set.of(
            "map-position", "preflight-checks", "ground-speed", "link-quality", "failsafe-banners",
            "battery", "visual-geolocation", "fleet-identity", "command-tx", "rc-relay", "video-ingest");

    public FeatureRequirement {
        if (featureKey == null || !FEATURE_KEYS.contains(featureKey)) {
            throw new IllegalArgumentException(
                    "featureKey must be one of the frozen v1 keys " + FEATURE_KEYS + ": " + featureKey);
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("FeatureRequirement label must not be blank");
        }
        if (firmware == null || firmware.isBlank()) {
            throw new IllegalArgumentException("FeatureRequirement firmware must not be blank");
        }
        if (requiredMessageId != null) {
            if (requiredMessageId < 0) {
                throw new IllegalArgumentException(
                        "requiredMessageId must not be negative: " + requiredMessageId);
            }
            if (requiredMessageName == null || requiredMessageName.isBlank()) {
                throw new IllegalArgumentException(
                        "requiredMessageName must not be blank when requiredMessageId is present");
            }
            if (minimumHz == null || minimumHz < 0) {
                throw new IllegalArgumentException(
                        "minimumHz must be non-negative when requiredMessageId is present: " + minimumHz);
            }
        }
        if (requiredParameterName != null && requiredParameterName.isBlank()) {
            throw new IllegalArgumentException("requiredParameterName must not be blank when present");
        }
    }
}

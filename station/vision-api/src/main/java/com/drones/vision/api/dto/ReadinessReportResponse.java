package com.drones.vision.api.dto;

import com.drones.vision.flight.domain.model.FeatureReadiness;
import com.drones.vision.flight.domain.model.ReadinessReport;

import java.time.Instant;
import java.util.List;

/**
 * Wire shape for {@code ReadinessReport} (docs/plans/active/DRONE-ONBOARDING-PLAN.md §8.1, frozen)
 * — {@code GET /api/assets/{assetId}/readiness} and {@code RemediationResult.reprobe} both answer
 * this exact shape.
 *
 * <p>No {@code @JsonInclude(NON_NULL)}: {@code profileObservedAt} must serialize as a literal
 * {@code null} when the asset was never probed (C7 — "never probed" is itself an honest, renderable
 * answer per {@link ReadinessReport}'s own javadoc), not be omitted.
 */
public record ReadinessReportResponse(
        String assetId,
        String verdict,
        Instant evaluatedAt,
        Instant profileObservedAt,
        List<FeatureReadinessResponse> features,
        List<String> blockers) {

    /**
     * One {@link ReadinessReport#features()} row. The domain's {@code featureKey} is renamed to
     * {@code "feature"} on the wire — §8.1's own frozen example spells it that way.
     */
    public record FeatureReadinessResponse(String feature, String label, String status, String detail,
                                            String remedy) {

        public static FeatureReadinessResponse from(FeatureReadiness readiness) {
            return new FeatureReadinessResponse(readiness.featureKey(), readiness.label(),
                    readiness.status().name(), readiness.detail(),
                    readiness.remedy() == null ? null : readiness.remedy().name());
        }
    }

    /**
     * Maps a domain {@link ReadinessReport} to its wire representation.
     *
     * @param report the report to map
     * @return the response body for {@code report}
     */
    public static ReadinessReportResponse from(ReadinessReport report) {
        return new ReadinessReportResponse(
                report.assetId().value().toString(),
                report.verdict().name(),
                report.evaluatedAt(),
                report.profileObservedAt(),
                report.features().stream().map(FeatureReadinessResponse::from).toList(),
                report.blockers());
    }
}

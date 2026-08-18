package com.drones.vision.api.dto;

import com.drones.vision.flight.domain.model.FeatureReadiness;
import com.drones.vision.flight.domain.model.ReadinessReport;
import com.drones.vision.warehouse.domain.model.Asset;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One row of {@code GET /api/fleet/readiness}'s {@code assets} array (docs/plans/active/
 * DRONE-ONBOARDING-PLAN.md §8.1, frozen) — a compact per-asset verdict-plus-feature-map, deliberately
 * flatter than {@link ReadinessReportResponse} (no {@code detail}/{@code remedy} text; the fleet
 * board renders a status grid, the per-asset page renders the full report).
 *
 * @param assetId     the asset's canonical id
 * @param displayName the asset's operator-facing name
 * @param verdict     the report's overall verdict ({@code "GO"|"NO_GO"|"UNKNOWN"})
 * @param features    {@code featureKey -> status}, one entry per row {@link ReadinessReport}
 *                    evaluated; insertion order matches the report's own feature order
 */
public record ReadinessRowResponse(String assetId, String displayName, String verdict, Map<String, String> features) {

    /**
     * Maps one asset plus its evaluated {@link ReadinessReport} to a fleet-board row.
     *
     * @param asset  the asset this row describes
     * @param report {@code asset}'s evaluated readiness
     * @return the fleet-board row
     */
    public static ReadinessRowResponse from(Asset asset, ReadinessReport report) {
        Map<String, String> features = new LinkedHashMap<>();
        for (FeatureReadiness readiness : report.features()) {
            features.put(readiness.featureKey(), readiness.status().name());
        }
        return new ReadinessRowResponse(asset.id().value().toString(), asset.displayName(), report.verdict().name(),
                features);
    }
}

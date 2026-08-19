package com.drones.vision.api.controller;

import com.drones.vision.api.dto.FleetReadinessResponse;
import com.drones.vision.api.dto.ReadinessReportResponse;
import com.drones.vision.api.dto.ReadinessRowResponse;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.flight.application.ReadinessService;
import com.drones.vision.flight.domain.model.ReadinessReport;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.asset.AssetSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Driving REST adapter for the onboarding pipeline's NEGOTIATE stage (docs/plans/active/
 * DRONE-ONBOARDING-PLAN.md §3.1, §8.1's frozen wire contract): read-only readiness, per-asset and
 * fleet-wide.
 *
 * <p>Constructor-injected with {@link ReadinessService} and {@link AssetService} — {@code GET
 * /api/fleet/readiness} composes the two directly (one visibility-scoped asset list, one readiness
 * evaluation per asset) rather than reaching for a new aggregator service in {@code vision-flight}
 * or {@code vision-warehouse}, mirroring {@link FleetController}'s own "one controller, thin
 * composition of exactly what it needs" precedent — there is no policy decision here, only reads.
 *
 * <h2>Status codes</h2>
 * Both endpoints are scoped reads: an unknown or out-of-scope asset {@code 404}s (hiding existence,
 * {@link java.util.NoSuchElementException} via {@link ReadinessService#evaluate}); {@code GET
 * /api/fleet/readiness} never 404s itself since it only ever asks about assets {@link
 * AssetService#assets} already filtered into scope. With auth disabled the scope is unbounded, so
 * both endpoints behave exactly as their unscoped equivalents.
 */
@RestController
public class ReadinessController {

    private final ReadinessService readinessService;
    private final AssetService assetService;
    private final CurrentUser currentUser;

    public ReadinessController(ReadinessService readinessService, AssetService assetService, CurrentUser currentUser) {
        this.readinessService = Objects.requireNonNull(readinessService, "readinessService must not be null");
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * One asset's readiness: its most recent {@code VehicleProfile} evaluated against the seeded
     * feature-requirement table.
     *
     * @param assetId the asset to evaluate, as a canonical UUID string
     * @return the full readiness report
     */
    @GetMapping("/api/assets/{assetId}/readiness")
    public ReadinessReportResponse readiness(@PathVariable String assetId) {
        ReadinessReport report = readinessService.evaluate(AssetId.of(assetId), currentUser.scope());
        return ReadinessReportResponse.from(report);
    }

    /**
     * The fleet board: one compact row per asset the caller's scope includes.
     *
     * @return every visible asset's verdict and per-feature status map
     */
    @GetMapping("/api/fleet/readiness")
    public FleetReadinessResponse fleetReadiness() {
        List<AssetSummary> summaries = assetService.assets(currentUser.scope(), false);
        List<ReadinessRowResponse> rows = summaries.stream()
                .map(summary -> ReadinessRowResponse.from(summary.asset(),
                        readinessService.evaluate(summary.asset().id(), currentUser.scope())))
                .toList();
        return new FleetReadinessResponse(rows);
    }
}

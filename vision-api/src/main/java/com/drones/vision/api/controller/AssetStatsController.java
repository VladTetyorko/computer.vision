package com.drones.vision.api.controller;

import com.drones.vision.api.dto.AssetStatsResponse;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.application.asset.AssetService;
import com.drones.vision.application.asset.AssetStatsService;
import com.drones.vision.domain.model.AssetId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Driving REST adapter for one asset's flight-utilization stats (docs/ASSET-MANAGER-PAGE-PLAN.md,
 * Wave A) — {@code GET /api/assets/{assetId}/stats}, the manager page's KPI tile row (total flight
 * time, flight count, first/last flown, average flight length, last-known battery).
 *
 * <p>Kept as its own controller rather than folded into {@link AssetController} — that class is
 * already at the five-constructor-parameter ceiling ({@code
 * .claude/skills/java-clean-code/SKILL.md} §3), and this endpoint's one real collaborator ({@link
 * AssetStatsService}) is otherwise unrelated to asset CRUD/streaming.
 *
 * <h2>404 for an unknown asset</h2>
 * {@link AssetStatsService} deliberately never checks whether {@code assetId} is a real, known
 * asset (see its own javadoc) — aggregating "whatever usages exist" is honestly correct even for
 * an unknown id (zero usages). So this controller resolves the 404 itself, by calling {@link
 * AssetService#details(AssetId)} first purely for its {@link java.util.NoSuchElementException}
 * side effect — the exact same check {@code GET /api/assets/{id}} relies on — before ever calling
 * {@link AssetStatsService#statsFor(AssetId)}. The (discarded) {@code AssetDetails} result is not
 * otherwise used; a malformed UUID fails earlier in {@link AssetId#of(String)} and maps to 400,
 * both via {@link ApiExceptionHandler}, same as every other asset-scoped endpoint.
 *
 * <p>Per the hexagonal dependency rule (ARCHITECTURE.md §2, enforced by ArchUnit), this module
 * depends only on {@code vision-domain} and {@code vision-application} — never on an adapter.
 */
@RestController
public class AssetStatsController {

    private final AssetService assetService;
    private final AssetStatsService assetStatsService;

    public AssetStatsController(AssetService assetService, AssetStatsService assetStatsService) {
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.assetStatsService = Objects.requireNonNull(assetStatsService, "assetStatsService must not be null");
    }

    /**
     * Fetches one asset's aggregated flight stats.
     *
     * @param id the asset id, as a canonical UUID string
     * @return the asset's flight stats
     */
    @GetMapping("/api/assets/{id}/stats")
    public AssetStatsResponse stats(@PathVariable String id) {
        AssetId assetId = AssetId.of(id);
        assetService.details(assetId); // 404s for an unknown asset; see class javadoc
        return AssetStatsResponse.from(assetStatsService.statsFor(assetId));
    }
}

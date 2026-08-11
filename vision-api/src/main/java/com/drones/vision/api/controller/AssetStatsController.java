package com.drones.vision.api.controller;

import com.drones.vision.api.dto.AssetStatsResponse;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.application.asset.AssetService;
import com.drones.vision.application.asset.AssetStatsService;
import com.drones.vision.application.scope.VisibilityScope;
import com.drones.vision.domain.model.AssetId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Driving REST adapter for one asset's flight-utilization stats (docs/plans/done/ASSET-MANAGER-PAGE-PLAN.md,
 * Wave A) — {@code GET /api/assets/{assetId}/stats}, the manager page's KPI tile row (total flight
 * time, flight count, first/last flown, average flight length, last-known battery).
 *
 * <p>Kept as its own controller rather than folded into {@link AssetController} — that class is
 * already at the five-constructor-parameter ceiling ({@code
 * .claude/skills/java-clean-code/SKILL.md} §3), and this endpoint's one real collaborator ({@link
 * AssetStatsService}) is otherwise unrelated to asset CRUD/streaming.
 *
 * <h2>404 for an unknown or out-of-scope asset (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2, feature 1)</h2>
 * {@link AssetStatsService} deliberately never checks whether {@code assetId} is a real, known
 * asset (see its own javadoc) — aggregating "whatever usages exist" is honestly correct even for
 * an unknown id (zero usages). So this controller resolves the 404 itself, by calling {@link
 * AssetService#details(VisibilityScope, AssetId)} first, scoped to {@link CurrentUser#scope()},
 * purely for its {@link java.util.NoSuchElementException} side effect — the exact same scoped check
 * {@code GET /api/assets/{id}} relies on — before ever calling {@link
 * AssetStatsService#statsFor(AssetId)}. An asset outside the caller's scope 404s exactly like an
 * unknown one, so this endpoint never reveals the existence of an asset the caller may not see. The
 * (discarded) {@code AssetDetails} result is not otherwise used; a malformed UUID fails earlier in
 * {@link AssetId#of(String)} and maps to 400, both via {@link ApiExceptionHandler}, same as every
 * other asset-scoped endpoint. With auth off (the default) the scope is unbounded, so this check is
 * a no-op and behavior is unchanged from before scoping.
 *
 * <p>Per the hexagonal dependency rule (ARCHITECTURE.md §2, enforced by ArchUnit), this module
 * depends only on {@code vision-domain} and {@code vision-application} — never on an adapter.
 */
@RestController
public class AssetStatsController {

    private final AssetService assetService;
    private final AssetStatsService assetStatsService;
    private final CurrentUser currentUser;

    public AssetStatsController(AssetService assetService, AssetStatsService assetStatsService,
                                 CurrentUser currentUser) {
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.assetStatsService = Objects.requireNonNull(assetStatsService, "assetStatsService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
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
        // 404s for an unknown or out-of-scope asset; see class javadoc.
        assetService.details(currentUser.scope(), assetId);
        return AssetStatsResponse.from(assetStatsService.statsFor(assetId));
    }
}

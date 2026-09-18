package com.drones.vision.api.controller;

import com.drones.vision.api.dto.LinkGroupResponse;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.flight.application.link.LinkStateService;
import com.drones.vision.flight.domain.model.LinkId;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.warehouse.application.asset.AssetService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Driving REST adapter for one asset's paired-link election state (LINK-PAIRING-PLAN.md §3.4 frozen
 * contract): {@code GET}/{@code PUT .../pin}/{@code DELETE .../pin} under {@code
 * /api/assets/{id}/links}. Station-wide carrier reference data is {@link CarriersController}, a
 * separate resource (§3.4/§7 ruling 5).
 *
 * <p>Constructor-injected with {@link LinkStateService}, {@link AssetService} (only to guard scope
 * — see {@link #requireInScope}) and {@link CurrentUser} (who a pin/release is attributed to).
 *
 * <h2>Visibility scoping</h2>
 * Every handler here re-reads the asset through {@link CurrentUser#scope()} before touching {@link
 * LinkStateService} — the same "read-scope guards the write" posture {@link
 * AssetStreamController} documents at length: {@link #requireInScope} 404s an out-of-scope or
 * unknown asset via {@link java.util.NoSuchElementException} (existence hidden), exactly like {@code
 * AssetStreamController#requireInScope}. No narrower "exclusive claim" concept (à la {@code
 * SeatAccess}) applies to link routing — a caller who may already see/fly this asset may also choose
 * which of its links carries traffic.
 */
@RestController
public class AssetLinksController {

    private final LinkStateService linkStateService;
    private final AssetService assetService;
    private final CurrentUser currentUser;

    public AssetLinksController(LinkStateService linkStateService, AssetService assetService,
                                 CurrentUser currentUser) {
        this.linkStateService = Objects.requireNonNull(linkStateService, "linkStateService must not be null");
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * @param id the asset to read, as a canonical UUID string
     * @return the asset's current link-election snapshot
     * @throws java.util.NoSuchElementException if the caller's scope may not reach this asset (404)
     */
    @GetMapping("/api/assets/{id}/links")
    public LinkGroupResponse links(@PathVariable String id) {
        AssetId assetId = AssetId.of(id);
        requireInScope(assetId);
        return LinkGroupResponse.from(linkStateService.linksFor(assetId));
    }

    /**
     * Operator override: pins the asset's ACTIVE link to {@code linkId}.
     *
     * @param id     the asset to pin, as a canonical UUID string
     * @param linkId the link to pin to
     * @return the resulting snapshot
     * @throws java.util.NoSuchElementException if the caller's scope may not reach this asset (404)
     * @throws IllegalArgumentException         if {@code linkId} is not one of this asset's known links (400)
     */
    @PutMapping("/api/assets/{id}/links/{linkId}/pin")
    public LinkGroupResponse pin(@PathVariable String id, @PathVariable String linkId) {
        AssetId assetId = AssetId.of(id);
        requireInScope(assetId);
        return LinkGroupResponse.from(linkStateService.pin(assetId, new LinkId(linkId), currentUser.userId()));
    }

    /**
     * Releases an operator pin on the asset's link group, if any, handing control back to automatic
     * election. Idempotent: releasing an unpinned asset is a no-op.
     *
     * @param id the asset to release, as a canonical UUID string
     * @return the resulting snapshot
     * @throws java.util.NoSuchElementException if the caller's scope may not reach this asset (404)
     */
    @DeleteMapping("/api/assets/{id}/links/pin")
    public LinkGroupResponse release(@PathVariable String id) {
        AssetId assetId = AssetId.of(id);
        requireInScope(assetId);
        return LinkGroupResponse.from(linkStateService.release(assetId, currentUser.userId()));
    }

    /**
     * Guards every handler above: re-reads {@code id} through the caller's scope so an out-of-scope
     * (or unknown) asset 404s before {@link LinkStateService} ever runs — matching {@code
     * AssetStreamController#requireInScope} exactly.
     */
    private void requireInScope(AssetId id) {
        assetService.details(currentUser.scope(), id);
    }
}

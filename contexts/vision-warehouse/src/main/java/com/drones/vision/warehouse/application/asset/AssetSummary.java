package com.drones.vision.warehouse.application.asset;

import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.Identity;
import com.drones.vision.warehouse.domain.model.InventoryState;
import com.drones.vision.kernel.GeoPosition;

import java.time.Instant;

/**
 * A read-model view of an asset for list rendering.
 *
 * <p>Users see this, not raw entities, so the view can be denormalized or cached later without
 * touching the API. {@code inventoryState} is the <b>effective</b> state (see {@code
 * InventoryStates#effective}), not necessarily {@link Asset#inventoryState()}'s stored value —
 * callers reading a summary should never need to also fetch usage state to know whether an asset
 * reads as issued or in the field. {@code identity}/{@code custody} mirror {@link
 * Asset#identity()}/{@link Asset#custody()} directly (not derived) — flattened here purely so a
 * list view never needs to reach through {@link #asset()} for them.
 *
 * @param asset             the underlying asset
 * @param categoryName      human-readable name of the asset's category; must not be blank
 * @param status            derived streaming status
 * @param lastUsedAt        start of the most recent usage, or {@code null} if never used
 * @param lastKnownPosition last known position across usages, or {@code null} if none is known
 * @param inventoryState    the effective inventory state (see {@code InventoryStates#effective})
 * @param identity          identity facts, or {@link Identity#NONE}
 * @param custody           current custody, or {@link Custody#NONE}
 */
public record AssetSummary(Asset asset, String categoryName, AssetStatus status, Instant lastUsedAt,
                            GeoPosition lastKnownPosition, InventoryState inventoryState, Identity identity,
                            Custody custody) {

    public AssetSummary {
        if (asset == null) {
            throw new IllegalArgumentException("AssetSummary asset must not be null");
        }
        if (categoryName == null || categoryName.isBlank()) {
            throw new IllegalArgumentException("AssetSummary categoryName must not be blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("AssetSummary status must not be null");
        }
        if (inventoryState == null) {
            throw new IllegalArgumentException("AssetSummary inventoryState must not be null");
        }
        if (identity == null) {
            throw new IllegalArgumentException("AssetSummary identity must not be null");
        }
        if (custody == null) {
            throw new IllegalArgumentException("AssetSummary custody must not be null");
        }
    }
}

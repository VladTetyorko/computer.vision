package com.drones.vision.application;

import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.GeoPosition;

import java.time.Instant;

/**
 * A read-model view of an asset for list rendering.
 *
 * <p>Users see this, not raw entities, so the view can be denormalized or cached later without
 * touching the API.
 *
 * @param asset             the underlying asset
 * @param categoryName      human-readable name of the asset's category; must not be blank
 * @param status            derived streaming status
 * @param lastUsedAt        start of the most recent usage, or {@code null} if never used
 * @param lastKnownPosition last known position across usages, or {@code null} if none is known
 */
public record AssetSummary(Asset asset, String categoryName, AssetStatus status, Instant lastUsedAt,
                            GeoPosition lastKnownPosition) {

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
    }
}

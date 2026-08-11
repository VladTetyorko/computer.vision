package com.drones.vision.warehouse.application.asset;

import com.drones.vision.flight.domain.model.AssetUsage;
import com.drones.vision.warehouse.domain.model.Device;

import java.util.List;

/**
 * The full detail view of one asset: its summary, its resolved devices, and recent usage history.
 *
 * @param summary      the same read model the list view shows
 * @param devices      the asset's resolved devices; defensively copied
 * @param recentUsages recent usages, newest first; defensively copied
 */
public record AssetDetails(AssetSummary summary, List<Device> devices, List<AssetUsage> recentUsages) {

    public AssetDetails {
        if (summary == null) {
            throw new IllegalArgumentException("AssetDetails summary must not be null");
        }
        if (devices == null) {
            throw new IllegalArgumentException("AssetDetails devices must not be null");
        }
        if (recentUsages == null) {
            throw new IllegalArgumentException("AssetDetails recentUsages must not be null");
        }
        devices = List.copyOf(devices);
        recentUsages = List.copyOf(recentUsages);
    }
}

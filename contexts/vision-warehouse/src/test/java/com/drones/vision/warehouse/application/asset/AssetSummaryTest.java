package com.drones.vision.warehouse.application.asset;

import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.warehouse.domain.model.Identity;
import com.drones.vision.warehouse.domain.model.InventoryState;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssetSummaryTest {

    private static Asset asset() {
        return Asset.register(AssetId.random(), "my drone", new CategoryId("drone"),
                new Ownership(UserId.random(), GroupId.random()), Set.of(DeviceId.random()), Map.of(), Identity.NONE,
                Custody.NONE);
    }

    @Test
    void rejectsMissingAssetCategoryNameStatusOrInventoryState() {
        assertThrows(IllegalArgumentException.class, () -> new AssetSummary(null, "Drone", AssetStatus.OFFLINE, null,
                null, InventoryState.IN_STOCK, Identity.NONE, Custody.NONE));
        assertThrows(IllegalArgumentException.class, () -> new AssetSummary(asset(), " ", AssetStatus.OFFLINE, null,
                null, InventoryState.IN_STOCK, Identity.NONE, Custody.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> new AssetSummary(asset(), "Drone", null, null, null, InventoryState.IN_STOCK, Identity.NONE,
                        Custody.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> new AssetSummary(asset(), "Drone", AssetStatus.OFFLINE, null, null, null, Identity.NONE,
                        Custody.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> new AssetSummary(asset(), "Drone", AssetStatus.OFFLINE, null, null, InventoryState.IN_STOCK,
                        null, Custody.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> new AssetSummary(asset(), "Drone", AssetStatus.OFFLINE, null, null, InventoryState.IN_STOCK,
                        Identity.NONE, null));
    }

    @Test
    void allowsAnAssetThatHasNeverBeenUsed() {
        // Never-used is the normal state of a freshly created asset, not an error.
        AssetSummary summary = new AssetSummary(asset(), "Drone", AssetStatus.OFFLINE, null, null,
                InventoryState.IN_STOCK, Identity.NONE, Custody.NONE);

        assertNull(summary.lastUsedAt());
        assertNull(summary.lastKnownPosition());
    }
}

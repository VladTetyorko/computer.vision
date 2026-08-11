package com.drones.vision.application.asset;

import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssetSummaryTest {

    private static Asset asset() {
        return new Asset(AssetId.random(), "my drone", new CategoryId("drone"),
                new Ownership(UserId.random(), GroupId.random()), Set.of(DeviceId.random()), Map.of());
    }

    @Test
    void rejectsMissingAssetCategoryNameOrStatus() {
        assertThrows(IllegalArgumentException.class,
                () -> new AssetSummary(null, "Drone", AssetStatus.OFFLINE, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new AssetSummary(asset(), " ", AssetStatus.OFFLINE, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new AssetSummary(asset(), "Drone", null, null, null));
    }

    @Test
    void allowsAnAssetThatHasNeverBeenUsed() {
        // Never-used is the normal state of a freshly created asset, not an error.
        AssetSummary summary = new AssetSummary(asset(), "Drone", AssetStatus.OFFLINE, null, null);

        assertNull(summary.lastUsedAt());
        assertNull(summary.lastKnownPosition());
    }
}

package com.drones.vision.warehouse.domain.model;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InventoryStatesTest {

    private static Asset asset(Custody custody, InventoryState inventoryState) {
        Instant now = Instant.now();
        return new Asset(AssetId.random(), "my drone", new CategoryId("drone"),
                new Ownership(UserId.random(), GroupId.random()), Set.of(DeviceId.random()), Map.of(),
                com.drones.vision.kernel.LifecycleState.ACTIVE, Identity.NONE, custody, inventoryState, now, now);
    }

    @Test
    void inStockWithNoOpenUsageReadsAsStored() {
        Asset asset = asset(Custody.NONE, InventoryState.IN_STOCK);

        assertEquals(InventoryState.IN_STOCK, InventoryStates.effective(asset, false));
    }

    @Test
    void custodianSetWithNoOpenUsageReadsAsIssued() {
        Asset asset = asset(new Custody(UserId.random(), "Hangar 2", Instant.now()), InventoryState.IN_STOCK);

        assertEquals(InventoryState.ISSUED, InventoryStates.effective(asset, false));
    }

    @Test
    void openUsageAlwaysReadsAsInFieldEvenWithACustodian() {
        Asset issued = asset(new Custody(UserId.random(), "Hangar 2", Instant.now()), InventoryState.IN_STOCK);
        Asset unissued = asset(Custody.NONE, InventoryState.IN_STOCK);

        assertEquals(InventoryState.IN_FIELD, InventoryStates.effective(issued, true));
        assertEquals(InventoryState.IN_FIELD, InventoryStates.effective(unissued, true));
    }

    @Test
    void maintenanceAndRetiredPassThroughWhenNoCustodianAndNoOpenUsage() {
        Asset inMaintenance = asset(Custody.NONE, InventoryState.MAINTENANCE);
        Asset retired = asset(Custody.NONE, InventoryState.RETIRED);

        assertEquals(InventoryState.MAINTENANCE, InventoryStates.effective(inMaintenance, false));
        assertEquals(InventoryState.RETIRED, InventoryStates.effective(retired, false));
    }

    @Test
    void rejectsNullAsset() {
        assertThrows(NullPointerException.class, () -> InventoryStates.effective(null, false));
    }
}

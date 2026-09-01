package com.drones.vision.api.support;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.asset.AssetStatus;
import com.drones.vision.warehouse.application.asset.AssetSummary;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.Identity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InventoryExportServiceTest {

    private static final CategoryId DRONE = new CategoryId("drone");

    @Test
    void toCsvRendersTheHeaderAloneWhenScopeSeesNoAssets() {
        AssetService assetService = mock(AssetService.class);
        VisibilityScope scope = VisibilityScope.unbounded();
        when(assetService.assets(scope, false)).thenReturn(List.of());
        InventoryExportService export = new InventoryExportService(assetService);

        String csv = export.toCsv(scope);

        assertEquals("id,name,category,serial,make,model,registration,inventoryState,custodian,location,"
                + "lifecycle,createdAt,lastFlownAt\n", csv);
    }

    @Test
    void toCsvRendersOneRowPerAssetWithIdentityAndCustodyFilledIn() {
        UserId custodian = UserId.random();
        Asset asset = Asset.register(AssetId.random(), "Drone, One", DRONE,
                new Ownership(UserId.random(), GroupId.random()), Set.of(DeviceId.random()), Map.of(),
                new Identity("SN-1", "DJI", "Mavic 3", null), new Custody(custodian, "Van 3", Instant.now()));
        AssetSummary summary = new AssetSummary(asset, "Drone", AssetStatus.OFFLINE, null, null,
                asset.inventoryState(), asset.identity(), asset.custody());
        AssetService assetService = mock(AssetService.class);
        VisibilityScope scope = VisibilityScope.unbounded();
        when(assetService.assets(scope, false)).thenReturn(List.of(summary));
        InventoryExportService export = new InventoryExportService(assetService);

        String csv = export.toCsv(scope);
        List<String> lines = csv.lines().toList();

        assertEquals(2, lines.size());
        String row = lines.get(1);
        assertEquals(asset.id().value().toString(), row.split(",", 2)[0]);
        assertEquals(true, row.contains("\"Drone, One\""));
        assertEquals(true, row.contains("SN-1"));
        assertEquals(true, row.contains(custodian.value().toString()));
        assertEquals(true, row.contains("Van 3"));
    }
}

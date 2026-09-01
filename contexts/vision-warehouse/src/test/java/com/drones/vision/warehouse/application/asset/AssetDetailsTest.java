package com.drones.vision.warehouse.application.asset;

import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.warehouse.domain.model.Identity;
import com.drones.vision.warehouse.domain.model.InventoryState;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssetDetailsTest {

    private static AssetSummary summary() {
        Asset asset = Asset.register(AssetId.random(), "my drone", new CategoryId("drone"),
                new Ownership(UserId.random(), GroupId.random()), Set.of(DeviceId.random()), Map.of(), Identity.NONE,
                Custody.NONE);
        return new AssetSummary(asset, "Drone", AssetStatus.OFFLINE, null, null, InventoryState.IN_STOCK,
                Identity.NONE, Custody.NONE);
    }

    private static Device device() {
        return new Device(DeviceId.random(), "cam", Set.of(Capability.VIDEO),
                new StreamDescriptor("sim", URI.create("sim://cam"), Map.of()));
    }

    @Test
    void rejectsMissingComponents() {
        assertThrows(IllegalArgumentException.class, () -> new AssetDetails(null, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AssetDetails(summary(), null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AssetDetails(summary(), List.of(), null));
    }

    @Test
    void allowsAnAssetWhoseDevicesNoLongerResolve() {
        // details() skips unresolvable device ids rather than failing the whole view.
        AssetDetails details = new AssetDetails(summary(), List.of(), List.of());

        assertEquals(List.of(), details.devices());
    }

    @Test
    void copiesTheDeviceAndUsageLists() {
        List<Device> devices = new ArrayList<>(List.of(device()));
        AssetDetails details = new AssetDetails(summary(), devices, List.of());

        devices.add(device());

        assertEquals(1, details.devices().size());
    }
}

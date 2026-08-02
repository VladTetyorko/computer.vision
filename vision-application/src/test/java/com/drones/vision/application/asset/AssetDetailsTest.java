package com.drones.vision.application.asset;

import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.UserId;
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
        Asset asset = new Asset(AssetId.random(), "my drone", new CategoryId("drone"),
                new Ownership(UserId.random(), GroupId.random()), Set.of(DeviceId.random()), Map.of());
        return new AssetSummary(asset, "Drone", AssetStatus.OFFLINE, null, null);
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

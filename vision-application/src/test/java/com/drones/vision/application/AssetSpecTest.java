package com.drones.vision.application;

import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.StreamDescriptor;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssetSpecTest {

    private static final CategoryId DRONE = new CategoryId("drone");

    private static DeviceRegistration device(String name) {
        return new DeviceRegistration(name, Set.of(Capability.VIDEO),
                new StreamDescriptor("sim", URI.create("sim://" + name), Map.of()));
    }

    @Test
    void rejectsBlankDisplayName() {
        assertThrows(IllegalArgumentException.class,
                () -> new AssetSpec(" ", DRONE, Map.of(), List.of(device("cam"))));
    }

    @Test
    void rejectsMissingCategoryOrAttributes() {
        assertThrows(IllegalArgumentException.class,
                () -> new AssetSpec("drone", null, Map.of(), List.of(device("cam"))));
        assertThrows(IllegalArgumentException.class,
                () -> new AssetSpec("drone", DRONE, null, List.of(device("cam"))));
    }

    @Test
    void rejectsZeroDevicesBecauseAnAssetIsNothingWithoutASource() {
        assertThrows(IllegalArgumentException.class, () -> new AssetSpec("drone", DRONE, Map.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AssetSpec("drone", DRONE, Map.of(), null));
    }

    @Test
    void copiesAttributesAndDevices() {
        List<DeviceRegistration> devices = new ArrayList<>(List.of(device("cam")));
        AssetSpec spec = new AssetSpec("drone", DRONE, Map.of("color", "red"), devices);

        devices.add(device("telemetry"));

        assertEquals(1, spec.devices().size());
        assertEquals(Map.of("color", "red"), spec.attributes());
    }

    @Test
    void fourArgConstructorDefaultsExistingDeviceIdsToEmpty() {
        AssetSpec spec = new AssetSpec("drone", DRONE, Map.of(), List.of(device("cam")));

        assertEquals(List.of(), spec.existingDeviceIds());
    }

    @Test
    void existingDeviceIdsAloneSatisfiesTheAtLeastOneDeviceRule() {
        AssetSpec spec = new AssetSpec("drone", DRONE, Map.of(), List.of(), List.of(DeviceId.random()));

        assertEquals(1, spec.existingDeviceIds().size());
        assertEquals(0, spec.devices().size());
    }

    @Test
    void rejectsZeroDevicesAndZeroExistingDeviceIdsCombined() {
        assertThrows(IllegalArgumentException.class,
                () -> new AssetSpec("drone", DRONE, Map.of(), List.of(), List.of()));
    }

    @Test
    void rejectsNullExistingDeviceIds() {
        assertThrows(IllegalArgumentException.class,
                () -> new AssetSpec("drone", DRONE, Map.of(), List.of(device("cam")), null));
    }

    @Test
    void copiesExistingDeviceIdsDefensively() {
        List<DeviceId> existingDeviceIds = new ArrayList<>(List.of(DeviceId.random()));
        AssetSpec spec = new AssetSpec("drone", DRONE, Map.of(), List.of(), existingDeviceIds);

        existingDeviceIds.add(DeviceId.random());

        assertEquals(1, spec.existingDeviceIds().size());
    }
}

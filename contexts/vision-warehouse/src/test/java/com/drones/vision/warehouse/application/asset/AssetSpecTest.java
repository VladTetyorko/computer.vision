package com.drones.vision.warehouse.application.asset;

import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.UserId;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.Identity;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.drones.vision.warehouse.application.device.DeviceRegistration;

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
    void rejectsNullDevices() {
        assertThrows(IllegalArgumentException.class, () -> new AssetSpec("drone", DRONE, Map.of(), null));
    }

    @Test
    void acceptsZeroDevicesForANonConnectedCategory() {
        // WAREHOUSE-UX-PLAN D4: "at least one device" is no longer this record's own invariant --
        // it depends on DeviceCategory#connected(), which this record cannot see, so
        // DefaultAssetService#create enforces it instead.
        AssetSpec spec = new AssetSpec("battery", DRONE, Map.of(), List.of());

        assertTrue(spec.devices().isEmpty());
        assertTrue(spec.existingDeviceIds().isEmpty());
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
    void fourArgConstructorDefaultsExistingDeviceIdsIdentityAndCustody() {
        AssetSpec spec = new AssetSpec("drone", DRONE, Map.of(), List.of(device("cam")));

        assertEquals(List.of(), spec.existingDeviceIds());
        assertEquals(Identity.NONE, spec.identity());
        assertEquals(Custody.NONE, spec.custody());
    }

    @Test
    void existingDeviceIdsCanStandAloneWithNoNewDevices() {
        AssetSpec spec = new AssetSpec("drone", DRONE, Map.of(), List.of(), List.of(DeviceId.random()), Identity.NONE,
                Custody.NONE);

        assertEquals(1, spec.existingDeviceIds().size());
        assertEquals(0, spec.devices().size());
    }

    @Test
    void acceptsZeroDevicesAndZeroExistingDeviceIdsCombined() {
        AssetSpec spec =
                new AssetSpec("drone", DRONE, Map.of(), List.of(), List.of(), Identity.NONE, Custody.NONE);

        assertTrue(spec.devices().isEmpty());
        assertTrue(spec.existingDeviceIds().isEmpty());
    }

    @Test
    void rejectsNullExistingDeviceIds() {
        assertThrows(IllegalArgumentException.class, () -> new AssetSpec("drone", DRONE, Map.of(),
                List.of(device("cam")), null, Identity.NONE, Custody.NONE));
    }

    @Test
    void rejectsNullIdentityOrCustody() {
        assertThrows(IllegalArgumentException.class, () -> new AssetSpec("drone", DRONE, Map.of(),
                List.of(device("cam")), List.of(), null, Custody.NONE));
        assertThrows(IllegalArgumentException.class, () -> new AssetSpec("drone", DRONE, Map.of(),
                List.of(device("cam")), List.of(), Identity.NONE, null));
    }

    @Test
    void carriesIdentityAndCustodySeededAtCreation() {
        Identity identity = new Identity("SN-1", "Acme", "X1", "N123AB");
        Custody custody = new Custody(UserId.random(), "Hangar 2", Instant.now());

        AssetSpec spec =
                new AssetSpec("drone", DRONE, Map.of(), List.of(device("cam")), List.of(), identity, custody);

        assertEquals(identity, spec.identity());
        assertEquals(custody, spec.custody());
    }

    @Test
    void copiesExistingDeviceIdsDefensively() {
        List<DeviceId> existingDeviceIds = new ArrayList<>(List.of(DeviceId.random()));
        AssetSpec spec = new AssetSpec("drone", DRONE, Map.of(), List.of(), existingDeviceIds, Identity.NONE,
                Custody.NONE);

        existingDeviceIds.add(DeviceId.random());

        assertEquals(1, spec.existingDeviceIds().size());
    }
}

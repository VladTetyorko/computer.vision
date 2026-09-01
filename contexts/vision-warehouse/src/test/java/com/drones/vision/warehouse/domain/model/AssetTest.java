package com.drones.vision.warehouse.domain.model;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.LifecycleState;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetTest {

    private static Ownership ownership() {
        return new Ownership(UserId.random(), GroupId.random());
    }

    private static Asset asset(Set<DeviceId> devices) {
        return Asset.register(AssetId.random(), "my drone", new CategoryId("drone"), ownership(), devices, Map.of(),
                Identity.NONE, Custody.NONE);
    }

    private static Asset canonical(AssetId id, String displayName, CategoryId category, Ownership ownership,
                                    Set<DeviceId> devices, Map<String, String> attributes) {
        Instant now = Instant.now();
        return new Asset(id, displayName, category, ownership, devices, attributes, LifecycleState.ACTIVE,
                Identity.NONE, Custody.NONE, InventoryState.IN_STOCK, now, now);
    }

    @Test
    void devicesAreDefensivelyCopied() {
        Set<DeviceId> devices = new HashSet<>();
        devices.add(DeviceId.random());

        Asset asset = asset(devices);
        devices.add(DeviceId.random());

        assertEquals(1, asset.devices().size(), "later mutation of the source set must not affect the asset");
        assertThrows(UnsupportedOperationException.class, () -> asset.devices().add(DeviceId.random()),
                "returned devices set must be immutable");
    }

    @Test
    void attributesAreDefensivelyCopied() {
        Map<String, String> attributes = new HashMap<>();
        attributes.put("color", "red");

        Asset asset = canonical(AssetId.random(), "my drone", new CategoryId("drone"), ownership(),
                Set.of(DeviceId.random()), attributes);
        attributes.put("weight", "500g");

        assertEquals(1, asset.attributes().size(), "later mutation of the source map must not affect the asset");
        assertThrows(UnsupportedOperationException.class, () -> asset.attributes().put("x", "y"),
                "returned attributes map must be immutable");
    }

    @Test
    void acceptsEmptyDevicesForANonConnectedCategory() {
        // WAREHOUSE-UX-PLAN D4: this record only requires devices to be non-null -- "at least one
        // device when connected" is an application-layer rule (DefaultAssetService#create), since
        // this record only holds a CategoryId, not the DeviceCategory.
        Asset asset = asset(Set.of());

        assertTrue(asset.devices().isEmpty());
    }

    @Test
    void rejectsInvalidArguments() {
        Set<DeviceId> devices = Set.of(DeviceId.random());
        Ownership ownership = ownership();
        CategoryId category = new CategoryId("drone");
        Instant now = Instant.now();

        assertThrows(IllegalArgumentException.class, () -> new Asset(null, "my drone", category, ownership, devices,
                Map.of(), LifecycleState.ACTIVE, Identity.NONE, Custody.NONE, InventoryState.IN_STOCK, now, now));
        assertThrows(IllegalArgumentException.class, () -> new Asset(AssetId.random(), null, category, ownership,
                devices, Map.of(), LifecycleState.ACTIVE, Identity.NONE, Custody.NONE, InventoryState.IN_STOCK, now,
                now));
        assertThrows(IllegalArgumentException.class, () -> new Asset(AssetId.random(), "", category, ownership,
                devices, Map.of(), LifecycleState.ACTIVE, Identity.NONE, Custody.NONE, InventoryState.IN_STOCK, now,
                now));
        assertThrows(IllegalArgumentException.class, () -> new Asset(AssetId.random(), "my drone", null, ownership,
                devices, Map.of(), LifecycleState.ACTIVE, Identity.NONE, Custody.NONE, InventoryState.IN_STOCK, now,
                now));
        assertThrows(IllegalArgumentException.class, () -> new Asset(AssetId.random(), "my drone", category, null,
                devices, Map.of(), LifecycleState.ACTIVE, Identity.NONE, Custody.NONE, InventoryState.IN_STOCK, now,
                now));
        assertThrows(IllegalArgumentException.class, () -> new Asset(AssetId.random(), "my drone", category,
                ownership, null, Map.of(), LifecycleState.ACTIVE, Identity.NONE, Custody.NONE,
                InventoryState.IN_STOCK, now, now));
        assertThrows(IllegalArgumentException.class, () -> new Asset(AssetId.random(), "my drone", category,
                ownership, devices, null, LifecycleState.ACTIVE, Identity.NONE, Custody.NONE,
                InventoryState.IN_STOCK, now, now));
        assertThrows(IllegalArgumentException.class, () -> new Asset(AssetId.random(), "my drone", category,
                ownership, devices, Map.of(), null, Identity.NONE, Custody.NONE, InventoryState.IN_STOCK, now, now));
        assertThrows(IllegalArgumentException.class, () -> new Asset(AssetId.random(), "my drone", category,
                ownership, devices, Map.of(), LifecycleState.ACTIVE, null, Custody.NONE, InventoryState.IN_STOCK,
                now, now));
        assertThrows(IllegalArgumentException.class, () -> new Asset(AssetId.random(), "my drone", category,
                ownership, devices, Map.of(), LifecycleState.ACTIVE, Identity.NONE, null, InventoryState.IN_STOCK,
                now, now));
        assertThrows(IllegalArgumentException.class, () -> new Asset(AssetId.random(), "my drone", category,
                ownership, devices, Map.of(), LifecycleState.ACTIVE, Identity.NONE, Custody.NONE, null, now, now));
        assertThrows(IllegalArgumentException.class, () -> new Asset(AssetId.random(), "my drone", category,
                ownership, devices, Map.of(), LifecycleState.ACTIVE, Identity.NONE, Custody.NONE,
                InventoryState.IN_STOCK, null, now));
        assertThrows(IllegalArgumentException.class, () -> new Asset(AssetId.random(), "my drone", category,
                ownership, devices, Map.of(), LifecycleState.ACTIVE, Identity.NONE, Custody.NONE,
                InventoryState.IN_STOCK, now, null));
    }

    @Test
    void rejectsADerivedInventoryStateAsTheStoredValue() {
        Set<DeviceId> devices = Set.of(DeviceId.random());
        Ownership ownership = ownership();
        CategoryId category = new CategoryId("drone");
        Instant now = Instant.now();

        assertThrows(IllegalArgumentException.class, () -> new Asset(AssetId.random(), "my drone", category,
                ownership, devices, Map.of(), LifecycleState.ACTIVE, Identity.NONE, Custody.NONE,
                InventoryState.ISSUED, now, now));
        assertThrows(IllegalArgumentException.class, () -> new Asset(AssetId.random(), "my drone", category,
                ownership, devices, Map.of(), LifecycleState.ACTIVE, Identity.NONE, Custody.NONE,
                InventoryState.IN_FIELD, now, now));
    }

    @Test
    void rejectsUpdatedAtBeforeCreatedAt() {
        Set<DeviceId> devices = Set.of(DeviceId.random());
        Ownership ownership = ownership();
        CategoryId category = new CategoryId("drone");
        Instant createdAt = Instant.now();
        Instant updatedAt = createdAt.minusSeconds(60);

        assertThrows(IllegalArgumentException.class, () -> new Asset(AssetId.random(), "my drone", category,
                ownership, devices, Map.of(), LifecycleState.ACTIVE, Identity.NONE, Custody.NONE,
                InventoryState.IN_STOCK, createdAt, updatedAt));
    }

    @Test
    void registerDefaultsToActiveInStockNow() {
        Asset asset = asset(Set.of(DeviceId.random()));

        assertTrue(asset.isActive());
        assertEquals(InventoryState.IN_STOCK, asset.inventoryState());
        assertEquals(Identity.NONE, asset.identity());
        assertEquals(Custody.NONE, asset.custody());
        assertEquals(asset.createdAt(), asset.updatedAt());
    }

    @Test
    void withDevicesReturnsNewInstance() {
        Asset original = asset(Set.of(DeviceId.random()));
        Set<DeviceId> newDevices = Set.of(DeviceId.random(), DeviceId.random());

        Asset updated = original.withDevices(newDevices);

        assertEquals(newDevices, updated.devices());
        assertEquals(1, original.devices().size(), "original instance must be unchanged");
    }

    @Test
    void withDevicesAcceptsEmptySet() {
        Asset original = asset(Set.of(DeviceId.random()));

        Asset updated = original.withDevices(Set.of());

        assertTrue(updated.devices().isEmpty());
    }

    @Test
    void withAttributesReturnsNewInstance() {
        Asset original = asset(Set.of(DeviceId.random()));

        Asset updated = original.withAttributes(Map.of("color", "red"));

        assertEquals(Map.of("color", "red"), updated.attributes());
        assertEquals(Map.of(), original.attributes(), "original instance must be unchanged");
    }

    @Test
    void withIdentityReplacesIdentityAndStampsUpdatedAt() {
        Asset original = asset(Set.of(DeviceId.random()));
        Identity identity = new Identity("SN-1", "Acme", "X1", "N123AB");
        Instant updatedAt = original.createdAt().plusSeconds(60);

        Asset updated = original.withIdentity(identity, updatedAt);

        assertEquals(identity, updated.identity());
        assertEquals(updatedAt, updated.updatedAt());
        assertEquals(Identity.NONE, original.identity(), "original instance must be unchanged");
    }

    @Test
    void withInventoryReplacesCustodyAndStateAndStampsUpdatedAt() {
        Asset original = asset(Set.of(DeviceId.random()));
        Custody custody = new Custody(UserId.random(), "Hangar 2", Instant.now());
        Instant updatedAt = original.createdAt().plusSeconds(60);

        Asset updated = original.withInventory(custody, InventoryState.MAINTENANCE, updatedAt);

        assertEquals(custody, updated.custody());
        assertEquals(InventoryState.MAINTENANCE, updated.inventoryState());
        assertEquals(updatedAt, updated.updatedAt());
        assertFalse(original.custody().equals(custody), "original instance must be unchanged");
    }

    @Test
    void touchOnlyStampsUpdatedAt() {
        Asset original = asset(Set.of(DeviceId.random()));
        Instant updatedAt = original.createdAt().plusSeconds(60);

        Asset touched = original.touch(updatedAt);

        assertEquals(updatedAt, touched.updatedAt());
        assertEquals(original.createdAt(), touched.createdAt());
        assertEquals(original.identity(), touched.identity());
        assertEquals(original.custody(), touched.custody());
        assertEquals(original.inventoryState(), touched.inventoryState());
    }
}

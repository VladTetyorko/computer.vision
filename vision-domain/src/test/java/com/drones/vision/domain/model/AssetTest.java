package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssetTest {

    private static Ownership ownership() {
        return new Ownership(UserId.random(), GroupId.random());
    }

    private static Asset asset(Set<DeviceId> devices) {
        return new Asset(AssetId.random(), "my drone", new CategoryId("drone"), ownership(), devices, Map.of());
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

        Asset asset = new Asset(AssetId.random(), "my drone", new CategoryId("drone"), ownership(),
                Set.of(DeviceId.random()), attributes);
        attributes.put("weight", "500g");

        assertEquals(1, asset.attributes().size(), "later mutation of the source map must not affect the asset");
        assertThrows(UnsupportedOperationException.class, () -> asset.attributes().put("x", "y"),
                "returned attributes map must be immutable");
    }

    @Test
    void rejectsEmptyDevices() {
        assertThrows(IllegalArgumentException.class, () -> asset(Set.of()));
    }

    @Test
    void rejectsInvalidArguments() {
        Set<DeviceId> devices = Set.of(DeviceId.random());
        Ownership ownership = ownership();
        CategoryId category = new CategoryId("drone");

        assertThrows(IllegalArgumentException.class,
                () -> new Asset(null, "my drone", category, ownership, devices, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Asset(AssetId.random(), null, category, ownership, devices, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Asset(AssetId.random(), "", category, ownership, devices, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Asset(AssetId.random(), "my drone", null, ownership, devices, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Asset(AssetId.random(), "my drone", category, null, devices, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Asset(AssetId.random(), "my drone", category, ownership, null, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Asset(AssetId.random(), "my drone", category, ownership, devices, null));
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
    void withDevicesRejectsEmptySet() {
        Asset original = asset(Set.of(DeviceId.random()));

        assertThrows(IllegalArgumentException.class, () -> original.withDevices(Set.of()));
    }

    @Test
    void withAttributesReturnsNewInstance() {
        Asset original = asset(Set.of(DeviceId.random()));

        Asset updated = original.withAttributes(Map.of("color", "red"));

        assertEquals(Map.of("color", "red"), updated.attributes());
        assertEquals(Map.of(), original.attributes(), "original instance must be unchanged");
    }
}

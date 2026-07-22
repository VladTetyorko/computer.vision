package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeviceTest {

    private static StreamDescriptor descriptor() {
        return new StreamDescriptor("sim", URI.create("sim://cam"), Map.of());
    }

    @Test
    void capabilitiesAreDefensivelyCopied() {
        Set<Capability> capabilities = EnumSet.of(Capability.VIDEO);

        Device device = new Device(DeviceId.random(), "cam-1", capabilities, descriptor());

        capabilities.add(Capability.AUDIO);

        assertEquals(Set.of(Capability.VIDEO), device.capabilities(),
                "later mutation of the source set must not affect the device");
        assertThrows(UnsupportedOperationException.class, () -> device.capabilities().add(Capability.PTZ),
                "returned capabilities set must be immutable");
    }

    @Test
    void rejectsInvalidArguments() {
        DeviceId id = DeviceId.random();
        Set<Capability> caps = Set.of(Capability.VIDEO);
        StreamDescriptor stream = descriptor();

        assertThrows(IllegalArgumentException.class, () -> new Device(null, "cam", caps, stream));
        assertThrows(IllegalArgumentException.class, () -> new Device(id, "", caps, stream));
        assertThrows(IllegalArgumentException.class, () -> new Device(id, "cam", null, stream));
        assertThrows(IllegalArgumentException.class, () -> new Device(id, "cam", caps, null));
    }
}

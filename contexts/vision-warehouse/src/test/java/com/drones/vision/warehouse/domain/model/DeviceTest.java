package com.drones.vision.warehouse.domain.model;

import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.DeviceOrigin;
import com.drones.vision.kernel.LifecycleState;
import com.drones.vision.kernel.StreamDescriptor;
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
        assertThrows(IllegalArgumentException.class,
                () -> new Device(id, "cam", caps, stream, LifecycleState.ACTIVE, null));
    }

    @Test
    void defaultsOriginToLiveWhenOmitted() {
        Device device = new Device(DeviceId.random(), "cam-1", Set.of(Capability.VIDEO), descriptor());

        assertEquals(DeviceOrigin.LIVE, device.origin());
    }

    @Test
    void canBeConstructedWithASimulatedOrigin() {
        Device device = new Device(DeviceId.random(), "cam-1", Set.of(Capability.VIDEO), descriptor(),
                LifecycleState.ACTIVE, DeviceOrigin.SIMULATED);

        assertEquals(DeviceOrigin.SIMULATED, device.origin());
    }

    @Test
    void withDetailsReplacesOrigin() {
        Device device = new Device(DeviceId.random(), "cam-1", Set.of(Capability.VIDEO), descriptor());

        Device updated = device.withDetails("cam-1", Set.of(Capability.VIDEO), descriptor(), DeviceOrigin.SIMULATED);

        assertEquals(DeviceOrigin.SIMULATED, updated.origin());
        assertEquals(device.id(), updated.id());
        assertEquals(device.state(), updated.state());
    }

    /**
     * Regression for U2 (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md &sect;3.2 B1): {@link
     * Device#withState} used to route through the 5-argument convenience constructor, which
     * silently defaults {@code origin} to {@link DeviceOrigin#LIVE} — so setting a simulated
     * device's lifecycle state, e.g. deactivating it, would flip it to a real one as a side effect.
     */
    @Test
    void withStatePreservesOrigin() {
        Device simulated = new Device(DeviceId.random(), "cam-1", Set.of(Capability.VIDEO), descriptor(),
                LifecycleState.ACTIVE, DeviceOrigin.SIMULATED);

        Device deactivated = simulated.withState(LifecycleState.DEACTIVATED);

        assertEquals(DeviceOrigin.SIMULATED, deactivated.origin(),
                "withState must not silently reset a simulated device's origin to LIVE");
        assertEquals(LifecycleState.DEACTIVATED, deactivated.state());
    }
}

package com.drones.vision.application.device;

import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.StreamDescriptor;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeviceRegistrationTest {

    private static final StreamDescriptor STREAM =
            new StreamDescriptor("sim", URI.create("sim://cam"), Map.of());

    @Test
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> new DeviceRegistration(" ", Set.of(Capability.VIDEO), STREAM));
        assertThrows(IllegalArgumentException.class,
                () -> new DeviceRegistration(null, Set.of(Capability.VIDEO), STREAM));
    }

    @Test
    void rejectsMissingOrEmptyCapabilities() {
        assertThrows(IllegalArgumentException.class, () -> new DeviceRegistration("cam", null, STREAM));
        assertThrows(IllegalArgumentException.class, () -> new DeviceRegistration("cam", Set.of(), STREAM));
    }

    @Test
    void rejectsMissingStream() {
        assertThrows(IllegalArgumentException.class,
                () -> new DeviceRegistration("cam", Set.of(Capability.VIDEO), null));
    }

    @Test
    void copiesCapabilitiesSoLaterMutationOfTheCallersSetCannotLeakIn() {
        Set<Capability> mutable = new HashSet<>(Set.of(Capability.VIDEO));
        DeviceRegistration registration = new DeviceRegistration("cam", mutable, STREAM);

        mutable.add(Capability.PTZ);

        assertEquals(Set.of(Capability.VIDEO), registration.capabilities());
    }
}

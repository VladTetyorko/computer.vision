package com.drones.vision.warehouse.application.device;

import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.DeviceOrigin;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeviceEditTest {

    @Test
    void everyFieldIsOptionalBecauseNullMeansLeaveUnchanged() {
        DeviceEdit edit = new DeviceEdit(null, null, null, null);

        assertNull(edit.name());
        assertNull(edit.capabilities());
        assertNull(edit.stream());
        assertNull(edit.origin());
    }

    @Test
    void nothingIsTheIdentityEdit() {
        assertNull(DeviceEdit.NOTHING.name());
        assertNull(DeviceEdit.NOTHING.capabilities());
        assertNull(DeviceEdit.NOTHING.stream());
        assertNull(DeviceEdit.NOTHING.origin());
    }

    @Test
    void rejectsAPresentButBlankName() {
        // Absent means "leave it"; present-and-blank is a mistake, not an instruction.
        assertThrows(IllegalArgumentException.class, () -> new DeviceEdit(" ", null, null, null));
    }

    @Test
    void rejectsAPresentButEmptyCapabilitySet() {
        assertThrows(IllegalArgumentException.class, () -> new DeviceEdit(null, Set.of(), null, null));
    }

    @Test
    void copiesAPresentCapabilitySet() {
        Set<Capability> mutable = new java.util.HashSet<>(Set.of(Capability.VIDEO));
        DeviceEdit edit = new DeviceEdit(null, mutable, null, null);

        mutable.add(Capability.AUDIO);

        assertThrows(UnsupportedOperationException.class, () -> edit.capabilities().add(Capability.PTZ));
        org.junit.jupiter.api.Assertions.assertEquals(Set.of(Capability.VIDEO), edit.capabilities());
    }

    @Test
    void canCarryAReplacementOrigin() {
        DeviceEdit edit = new DeviceEdit(null, null, null, DeviceOrigin.SIMULATED);

        org.junit.jupiter.api.Assertions.assertEquals(DeviceOrigin.SIMULATED, edit.origin());
    }
}

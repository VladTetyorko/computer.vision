package com.drones.vision.application;

import com.drones.vision.domain.model.Capability;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeviceEditTest {

    @Test
    void everyFieldIsOptionalBecauseNullMeansLeaveUnchanged() {
        DeviceEdit edit = new DeviceEdit(null, null, null);

        assertNull(edit.name());
        assertNull(edit.capabilities());
        assertNull(edit.stream());
    }

    @Test
    void nothingIsTheIdentityEdit() {
        assertNull(DeviceEdit.NOTHING.name());
        assertNull(DeviceEdit.NOTHING.capabilities());
        assertNull(DeviceEdit.NOTHING.stream());
    }

    @Test
    void rejectsAPresentButBlankName() {
        // Absent means "leave it"; present-and-blank is a mistake, not an instruction.
        assertThrows(IllegalArgumentException.class, () -> new DeviceEdit(" ", null, null));
    }

    @Test
    void rejectsAPresentButEmptyCapabilitySet() {
        assertThrows(IllegalArgumentException.class, () -> new DeviceEdit(null, Set.of(), null));
    }

    @Test
    void copiesAPresentCapabilitySet() {
        Set<Capability> mutable = new java.util.HashSet<>(Set.of(Capability.VIDEO));
        DeviceEdit edit = new DeviceEdit(null, mutable, null);

        mutable.add(Capability.AUDIO);

        assertThrows(UnsupportedOperationException.class, () -> edit.capabilities().add(Capability.PTZ));
        org.junit.jupiter.api.Assertions.assertEquals(Set.of(Capability.VIDEO), edit.capabilities());
    }
}

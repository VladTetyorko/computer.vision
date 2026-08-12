package com.drones.vision.identity.application;

import com.drones.vision.kernel.GroupId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GroupSpecTest {

    @Test
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> new GroupSpec(" ", GroupId.random()));
        assertThrows(IllegalArgumentException.class, () -> new GroupSpec(null, GroupId.random()));
    }

    @Test
    void acceptsNullParentGroupId() {
        GroupSpec spec = new GroupSpec("root", null);

        assertNull(spec.parentGroupId());
    }
}

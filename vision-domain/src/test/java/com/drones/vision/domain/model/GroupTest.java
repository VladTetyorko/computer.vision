package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GroupTest {

    @Test
    void rejectsNullId() {
        assertThrows(IllegalArgumentException.class, () -> new Group(null, "hq", null));
    }

    @Test
    void rejectsNullName() {
        assertThrows(IllegalArgumentException.class, () -> new Group(GroupId.random(), null, null));
    }

    @Test
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> new Group(GroupId.random(), "   ", null));
    }

    @Test
    void acceptsNullParentGroupIdAsRoot() {
        Group group = assertDoesNotThrow(() -> new Group(GroupId.random(), "hq", null));

        assertNull(group.parentGroupId());
    }

    @Test
    void acceptsNonNullParentGroupId() {
        GroupId parent = GroupId.random();

        Group group = new Group(GroupId.random(), "fleet-1", parent);

        assertEquals(parent, group.parentGroupId());
    }
}

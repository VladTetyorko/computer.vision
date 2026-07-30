package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MembershipTest {

    @Test
    void rejectsNullGroupId() {
        assertThrows(IllegalArgumentException.class, () -> new Membership(null, Role.PILOT));
    }

    @Test
    void rejectsNullRole() {
        assertThrows(IllegalArgumentException.class, () -> new Membership(GroupId.random(), null));
    }

    @Test
    void acceptsValidArguments() {
        GroupId groupId = GroupId.random();

        Membership membership = new Membership(groupId, Role.MANAGER);

        assertEquals(groupId, membership.groupId());
        assertEquals(Role.MANAGER, membership.role());
    }
}

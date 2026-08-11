package com.drones.vision.kernel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OwnershipTest {

    @Test
    void acceptsValidArguments() {
        UserId userId = UserId.random();
        GroupId groupId = GroupId.random();

        Ownership ownership = new Ownership(userId, groupId);

        assertEquals(userId, ownership.ownerId());
        assertEquals(groupId, ownership.groupId());
    }

    @Test
    void rejectsNullArguments() {
        UserId userId = UserId.random();
        GroupId groupId = GroupId.random();

        assertThrows(IllegalArgumentException.class, () -> new Ownership(null, groupId));
        assertThrows(IllegalArgumentException.class, () -> new Ownership(userId, null));
    }
}

package com.drones.vision.identity.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link Role}'s declared (ordinal/authority) order — docs/plans/active/AUTH-ROLES-PLAN.md §3.2's
 * "a prepend is not a reorder" claim, verified rather than assumed: {@link Role#VIEWER}'s addition
 * must not have disturbed the pre-existing {@code PILOT < MANAGER < ADMIN} relative order.
 */
class RoleTest {

    @Test
    void viewerIsTheLeastAuthority() {
        assertTrue(Role.VIEWER.compareTo(Role.PILOT) < 0);
        assertTrue(Role.VIEWER.compareTo(Role.MANAGER) < 0);
        assertTrue(Role.VIEWER.compareTo(Role.ADMIN) < 0);
    }

    @Test
    void relativeOrderOfThePreExistingThreeIsUnchangedByThePrepend() {
        assertTrue(Role.PILOT.compareTo(Role.MANAGER) < 0);
        assertTrue(Role.MANAGER.compareTo(Role.ADMIN) < 0);
        assertTrue(Role.PILOT.compareTo(Role.ADMIN) < 0);
    }

    @Test
    void adminIsTheMostAuthority() {
        assertTrue(Role.ADMIN.compareTo(Role.VIEWER) > 0);
        assertTrue(Role.ADMIN.compareTo(Role.PILOT) > 0);
        assertTrue(Role.ADMIN.compareTo(Role.MANAGER) > 0);
    }
}

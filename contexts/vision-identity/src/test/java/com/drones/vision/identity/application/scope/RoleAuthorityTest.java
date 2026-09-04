package com.drones.vision.identity.application.scope;

import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.platform.Capability;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the frozen Role &rarr; Capability table (docs/plans/active/AUTH-ROLES-PLAN.md §3.3). */
class RoleAuthorityTest {

    @Test
    void viewerHoldsNoCapabilities() {
        assertTrue(RoleAuthority.capabilitiesOf(Role.VIEWER).isEmpty());
    }

    @Test
    void pilotHoldsPayloadAndFlightOnly() {
        assertEquals(Set.of(Capability.OPERATE_PAYLOAD, Capability.COMMAND_FLIGHT),
                RoleAuthority.capabilitiesOf(Role.PILOT));
    }

    @Test
    void managerHoldsEveryCapability() {
        assertEquals(Set.of(Capability.values()), RoleAuthority.capabilitiesOf(Role.MANAGER));
    }

    @Test
    void adminHoldsEveryCapability() {
        assertEquals(Set.of(Capability.values()), RoleAuthority.capabilitiesOf(Role.ADMIN));
    }

    @Test
    void managerAndAdminAreIdenticalAtTheCapabilityLevel() {
        // The two are separated on the scope axis (GROUPS vs UNBOUNDED / canAdminister()), never by
        // a fifth capability -- see this class's own javadoc table.
        assertEquals(RoleAuthority.capabilitiesOf(Role.MANAGER), RoleAuthority.capabilitiesOf(Role.ADMIN));
    }

    @Test
    void resultIsImmutable() {
        Set<Capability> capabilities = RoleAuthority.capabilitiesOf(Role.PILOT);

        assertThrows(UnsupportedOperationException.class, () -> capabilities.add(Capability.MANAGE_ORG));
    }

    @Test
    void rejectsNullRole() {
        assertThrows(NullPointerException.class, () -> RoleAuthority.capabilitiesOf(null));
    }
}

package com.drones.vision.platform;

import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorityTest {

    private static Ownership ownershipIn(GroupId group) {
        return new Ownership(UserId.random(), group);
    }

    // --- full(): the identity element ------------------------------------------------

    @Test
    void fullWrapsUnboundedAndHoldsEveryCapability() {
        Authority full = Authority.full();

        assertTrue(full.scope().isUnbounded());
        assertEquals(Set.of(Capability.values()), full.capabilities());
    }

    @Test
    void fullPassesEveryGate() {
        Authority full = Authority.full();

        assertTrue(full.mayManageOrg());
        assertTrue(full.mayAdminister());
        assertTrue(full.mayManageFleet(ownershipIn(GroupId.random())));
    }

    // --- mayManageOrg(): capability AND scope ------------------------------------------

    @Test
    void mayManageOrgRequiresBothTheCapabilityAndAManagingScope() {
        Authority withCapNoScope = new Authority(VisibilityScope.assignedAssets(Set.of()),
                Set.of(Capability.MANAGE_ORG));
        assertFalse(withCapNoScope.mayManageOrg(), "capability without a managing scope must not pass");

        Authority withScopeNoCap = new Authority(VisibilityScope.groups(Set.of(GroupId.random())), Set.of());
        assertFalse(withScopeNoCap.mayManageOrg(), "a managing scope without the capability must not pass");

        Authority withBoth = new Authority(VisibilityScope.groups(Set.of(GroupId.random())),
                Set.of(Capability.MANAGE_ORG));
        assertTrue(withBoth.mayManageOrg());
    }

    // --- mayAdminister(): capability AND unbounded scope -------------------------------

    @Test
    void mayAdministerRequiresUnboundedScopeEvenWithTheCapability() {
        Authority managerLike = new Authority(VisibilityScope.groups(Set.of(GroupId.random())),
                Set.of(Capability.MANAGE_ORG));
        assertFalse(managerLike.mayAdminister(), "a GROUPS scope must never administer, capability or not");

        Authority adminLike = new Authority(VisibilityScope.unbounded(), Set.of(Capability.MANAGE_ORG));
        assertTrue(adminLike.mayAdminister());
    }

    @Test
    void mayAdministerRequiresTheCapabilityEvenWithUnboundedScope() {
        Authority noCapability = new Authority(VisibilityScope.unbounded(), Set.of());

        assertFalse(noCapability.mayAdminister(), "unbounded scope alone must not imply the capability");
    }

    // --- mayManageFleet(Ownership): capability AND scope.canManage ----------------------

    @Test
    void mayManageFleetRequiresBothTheCapabilityAndAManagingScopeForThatOwnership() {
        GroupId managed = GroupId.random();
        Ownership inGroup = ownershipIn(managed);
        Ownership outsideGroup = ownershipIn(GroupId.random());

        Authority manager = new Authority(VisibilityScope.groups(Set.of(managed)), Set.of(Capability.MANAGE_FLEET));
        assertTrue(manager.mayManageFleet(inGroup));
        assertFalse(manager.mayManageFleet(outsideGroup), "scope must actually cover this ownership's group");

        Authority noCapability = new Authority(VisibilityScope.groups(Set.of(managed)), Set.of());
        assertFalse(noCapability.mayManageFleet(inGroup), "capability without which is missing must not pass");

        Authority pilotScope = new Authority(VisibilityScope.assignedAssets(Set.of()), Set.of(Capability.MANAGE_FLEET));
        assertFalse(pilotScope.mayManageFleet(inGroup), "ASSIGNED_ASSETS scope can never manage a fleet");
    }

    @Test
    void mayManageFleetRejectsNullOwnership() {
        assertThrows(NullPointerException.class, () -> Authority.full().mayManageFleet(null));
    }

    // --- construction / defensive copy ---------------------------------------------------

    @Test
    void constructorRejectsNullScope() {
        assertThrows(NullPointerException.class, () -> new Authority(null, Set.of()));
    }

    @Test
    void nullCapabilitiesNormalizesToEmpty() {
        Authority authority = new Authority(VisibilityScope.unbounded(), null);

        assertTrue(authority.capabilities().isEmpty());
    }

    @Test
    void capabilitiesAreDefensivelyCopied() {
        Set<Capability> mutable = new HashSet<>(EnumSet.of(Capability.COMMAND_FLIGHT));
        Authority authority = new Authority(VisibilityScope.unbounded(), mutable);

        mutable.add(Capability.MANAGE_ORG);

        assertEquals(Set.of(Capability.COMMAND_FLIGHT), authority.capabilities(),
                "later mutation of the source set must not affect the authority");
        assertThrows(UnsupportedOperationException.class,
                () -> authority.capabilities().add(Capability.MANAGE_FLEET),
                "returned capabilities set must be immutable");
    }
}

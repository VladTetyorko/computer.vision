package com.drones.vision.api.security;

import com.drones.vision.identity.domain.model.AssignmentRole;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.identity.domain.port.AssignmentRepositoryPort;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.LifecycleState;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.map.application.MapAccessPolicy;
import com.drones.vision.platform.Authority;
import com.drones.vision.platform.Capability;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.application.asset.AssetDetails;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.asset.AssetStatus;
import com.drones.vision.warehouse.application.asset.AssetSummary;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.Identity;
import com.drones.vision.warehouse.domain.model.InventoryState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Test matrix for the one real {@link AssetAuthority} (docs/plans/active/AUTH-ROLES-PLAN.md §3.9,
 * wave B4) — a {@link Capability}, paired with {@link VisibilityScope#includes}, narrowed once more
 * on {@link CapabilityAssetAuthority#mayFly} by the caller's own assignment seat (IC-2). Exercises
 * both {@link CapabilityAssetAuthority#mayFly(AssetId)} (the ambient {@link CurrentUser} entry point
 * every {@link AssetAuthority} caller uses) and {@link
 * CapabilityAssetAuthority#mayFly(Authority, UserId, AssetId)} (the explicit-actor overload {@code
 * ManualControlWebSocketHandler} uses instead, since its message-handling thread has no {@code
 * SecurityContext} — see that class's own javadoc) to prove the two entry points apply the identical
 * rule.
 */
class CapabilityAssetAuthorityTest {

    private final AssetId assetId = AssetId.random();
    private final GroupId assetGroupId = GroupId.random();
    private final Ownership assetOwnership = new Ownership(UserId.random(), assetGroupId);

    private AssetService assetService;
    private AssignmentRepositoryPort assignmentRepository;

    @BeforeEach
    void setUp() {
        assetService = mock(AssetService.class);
        when(assetService.details(assetId)).thenReturn(assetDetails(assetOwnership));
        assignmentRepository = mock(AssignmentRepositoryPort.class);
    }

    // ---- mayFly: ambient CurrentUser entry point ----

    @Test
    void pilotSeatOnAnAssignedAssetMayFly() {
        UserId pilot = UserId.random();
        when(assignmentRepository.roleFor(pilot, assetId)).thenReturn(Optional.of(AssignmentRole.PILOT));
        Authority authority = new Authority(VisibilityScope.assignedAssets(Set.of(assetId)),
                Set.of(Capability.COMMAND_FLIGHT));

        assertTrue(authorityGateFor(pilot, authority).mayFly(assetId));
    }

    @Test
    void crewSeatOnAnAssignedAssetMayNotFly() {
        UserId crew = UserId.random();
        when(assignmentRepository.roleFor(crew, assetId)).thenReturn(Optional.of(AssignmentRole.CREW));
        Authority authority = new Authority(VisibilityScope.assignedAssets(Set.of(assetId)),
                Set.of(Capability.COMMAND_FLIGHT));

        assertFalse(authorityGateFor(crew, authority).mayFly(assetId));
    }

    @Test
    void noAssignmentLinkAtAllMayNotFly() {
        UserId stranger = UserId.random();
        when(assignmentRepository.roleFor(stranger, assetId)).thenReturn(Optional.empty());
        Authority authority = new Authority(VisibilityScope.assignedAssets(Set.of(assetId)),
                Set.of(Capability.COMMAND_FLIGHT));

        assertFalse(authorityGateFor(stranger, authority).mayFly(assetId));
    }

    @Test
    void viewerWithNoCapabilitiesMayNotFlyOrOperateCamera() {
        UserId viewer = UserId.random();
        Authority authority = new Authority(VisibilityScope.assignedAssets(Set.of(assetId)), Set.of());

        CapabilityAssetAuthority authorityGate = authorityGateFor(viewer, authority);
        assertFalse(authorityGate.mayFly(assetId));
        assertFalse(authorityGate.mayOperateCamera(assetId));
    }

    @Test
    void managerWithGroupScopeAndFullCapabilitiesMayFlyWithNoSeatLookup() {
        UserId manager = UserId.random();
        Authority authority = new Authority(VisibilityScope.groups(Set.of(assetGroupId)),
                EnumSet.allOf(Capability.class));

        assertTrue(authorityGateFor(manager, authority).mayFly(assetId));
        // The seat narrowing (IC-2) is a no-op for GROUPS/UNBOUNDED scopes -- confirms the lookup is
        // never even attempted for a MANAGER, not merely that its answer is ignored.
        verifyNoInteractions(assignmentRepository);
    }

    @Test
    void managerVisibilityDeniesAnAssetOutsideTheirGroups() {
        UserId manager = UserId.random();
        Authority authority = new Authority(VisibilityScope.groups(Set.of(GroupId.random())),
                EnumSet.allOf(Capability.class));

        assertFalse(authorityGateFor(manager, authority).mayFly(assetId));
    }

    // ---- mayFly: explicit-actor overload (ManualControlWebSocketHandler's entry point) ----

    @Test
    void explicitActorOverloadGrantsThePilotSeatIdenticallyToTheAmbientEntryPoint() {
        UserId pilot = UserId.random();
        when(assignmentRepository.roleFor(pilot, assetId)).thenReturn(Optional.of(AssignmentRole.PILOT));
        Authority authority = new Authority(VisibilityScope.assignedAssets(Set.of(assetId)),
                Set.of(Capability.COMMAND_FLIGHT));

        assertTrue(unrelatedCurrentUserGate().mayFly(authority, pilot, assetId));
    }

    @Test
    void explicitActorOverloadDeniesACrewSeatIdenticallyToTheAmbientEntryPoint() {
        UserId crew = UserId.random();
        when(assignmentRepository.roleFor(crew, assetId)).thenReturn(Optional.of(AssignmentRole.CREW));
        Authority authority = new Authority(VisibilityScope.assignedAssets(Set.of(assetId)),
                Set.of(Capability.COMMAND_FLIGHT));

        assertFalse(unrelatedCurrentUserGate().mayFly(authority, crew, assetId));
    }

    // ---- mayOperateCamera ----

    @Test
    void pilotWithOperatePayloadCapabilityMayOperateCameraOnTheirAssignedAsset() {
        UserId pilot = UserId.random();
        Authority authority = new Authority(VisibilityScope.assignedAssets(Set.of(assetId)),
                Set.of(Capability.OPERATE_PAYLOAD));

        assertTrue(authorityGateFor(pilot, authority).mayOperateCamera(assetId));
    }

    @Test
    void commandFlightAloneDoesNotGrantOperateCamera() {
        UserId pilot = UserId.random();
        Authority authority = new Authority(VisibilityScope.assignedAssets(Set.of(assetId)),
                Set.of(Capability.COMMAND_FLIGHT));

        assertFalse(authorityGateFor(pilot, authority).mayOperateCamera(assetId));
    }

    // ---- mayForceSeat mirrors Authority#mayManageFleet ----

    @Test
    void mayForceSeatMirrorsAuthorityMayManageFleet() {
        UserId manager = UserId.random();
        Authority authority = new Authority(VisibilityScope.groups(Set.of(assetGroupId)),
                Set.of(Capability.MANAGE_FLEET));

        assertTrue(authorityGateFor(manager, authority).mayForceSeat(assetId));
        assertTrue(authority.mayManageFleet(assetOwnership));
    }

    @Test
    void mayForceSeatDeniedWithoutManageFleetCapability() {
        UserId manager = UserId.random();
        Authority authority = new Authority(VisibilityScope.groups(Set.of(assetGroupId)), Set.of());

        assertFalse(authorityGateFor(manager, authority).mayForceSeat(assetId));
        assertFalse(authority.mayManageFleet(assetOwnership));
    }

    // ---- fixtures ----

    private CapabilityAssetAuthority authorityGateFor(UserId actor, Authority authority) {
        return new CapabilityAssetAuthority(new CurrentUser(fixedResolver(actor, authority)), assetService,
                assignmentRepository);
    }

    /**
     * A gate whose ambient {@link CurrentUser} is never consulted — every test using this exercises
     * only {@link CapabilityAssetAuthority#mayFly(Authority, UserId, AssetId)}, which reads its actor
     * and authority from its own parameters, exactly as {@code ManualControlWebSocketHandler} does
     * from its stashed WebSocket session attributes rather than from {@link CurrentUser}.
     */
    private CapabilityAssetAuthority unrelatedCurrentUserGate() {
        return new CapabilityAssetAuthority(new CurrentUser(fixedResolver(UserId.random(), Authority.full())),
                assetService, assignmentRepository);
    }

    private static PrincipalResolver fixedResolver(UserId actor, Authority authority) {
        return new PrincipalResolver() {
            @Override
            public UserId userId() {
                return actor;
            }

            @Override
            public Ownership ownership() {
                throw new UnsupportedOperationException("not exercised by CapabilityAssetAuthority");
            }

            @Override
            public VisibilityScope scope() {
                return authority.scope();
            }

            @Override
            public MapAccessPolicy.Viewer viewer() {
                throw new UnsupportedOperationException("not exercised by CapabilityAssetAuthority");
            }

            @Override
            public Role role() {
                throw new UnsupportedOperationException("not exercised by CapabilityAssetAuthority");
            }

            @Override
            public Authority authority() {
                return authority;
            }
        };
    }

    private AssetDetails assetDetails(Ownership ownership) {
        Instant now = Instant.now();
        Asset asset = new Asset(assetId, "Drone One", new CategoryId("drone"), ownership, Set.of(DeviceId.random()),
                Map.of(), LifecycleState.ACTIVE, Identity.NONE, Custody.NONE, InventoryState.IN_STOCK, now, now);
        AssetSummary summary = new AssetSummary(asset, "Drone", AssetStatus.OFFLINE, null, null,
                asset.inventoryState(), asset.identity(), asset.custody());
        return new AssetDetails(summary, List.of(), List.of());
    }
}

package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.security.PrincipalResolver;
import com.drones.vision.map.application.MapAccessPolicy;
import com.drones.vision.platform.Capability;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.platform.Authority;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.warehouse.application.directory.AssetDirectoryService;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.Identity;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests for {@link AuditController} (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2's
 * ADMIN/MANAGER management gate on the fleet-wide audit trail; docs/plans/active/AUTH-ROLES-PLAN.md
 * wave B6's {@code Authority#mayManageOrg()} migration and D18a subtree filter). Verifies the
 * admission check the controller performs directly (there is no application service to put it in —
 * see the class javadoc), the D18a subtree filter, plus its pre-existing wiring/validation behavior.
 */
class AuditControllerTest {

    private final AuditTrailPort auditTrail = mock(AuditTrailPort.class);
    private final AssetDirectoryService assetDirectoryService = mock(AssetDirectoryService.class);

    /**
     * A {@link CurrentUser} whose {@link CurrentUser#authority()} wraps exactly {@code scope} with
     * {@code capabilities} — unlike {@code new CurrentUser(Ownership)}, which always resolves to
     * {@link Authority#full()}, this lets a test exercise the MANAGER/PILOT/unaffiliated cases
     * {@link AuditController} gates on. Every other {@link PrincipalResolver} answer is a fixed,
     * unused-by-this-controller stand-in.
     */
    private static CurrentUser currentUserWithAuthority(VisibilityScope scope, Set<Capability> capabilities) {
        Ownership ownership = new Ownership(UserId.random(), GroupId.random());
        Authority authority = new Authority(scope, capabilities);
        return new CurrentUser(new PrincipalResolver() {
            @Override
            public UserId userId() {
                return ownership.ownerId();
            }

            @Override
            public Ownership ownership() {
                return ownership;
            }

            @Override
            public VisibilityScope scope() {
                return scope;
            }

            @Override
            public MapAccessPolicy.Viewer viewer() {
                return new MapAccessPolicy.Viewer(ownership.ownerId(), Set.of(ownership.groupId()), Role.PILOT);
            }

            @Override
            public Role role() {
                throw new UnsupportedOperationException("AuditController never calls role()");
            }

            @Override
            public Authority authority() {
                return authority;
            }
        });
    }

    private static MockMvc mockMvcFor(CurrentUser currentUser, AuditTrailPort auditTrail,
                                       AssetDirectoryService assetDirectoryService) {
        return MockMvcBuilders.standaloneSetup(new AuditController(auditTrail, assetDirectoryService, currentUser))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static Asset assetWithOwnership(Ownership ownership) {
        return Asset.register(AssetId.random(), "asset", new com.drones.vision.kernel.CategoryId("drone"),
                ownership, Set.of(), Map.of(), Identity.NONE, Custody.NONE);
    }

    @Test
    void listSucceedsForAnAdminUnboundedScope() throws Exception {
        AuditEntry entry = AuditEntry.of(UserId.random(), AuditAction.CREATED, AuditTargetType.ASSET,
                AssetId.random().value().toString(), "created asset");
        when(auditTrail.findRecent(anyInt())).thenReturn(List.of(entry));

        MockMvc mockMvc = mockMvcFor(currentUserWithAuthority(VisibilityScope.unbounded(), Authority.full()
                .capabilities()), auditTrail, assetDirectoryService);

        mockMvc.perform(get("/api/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].summary").value("created asset"));
        verifyNoInteractions(assetDirectoryService);
    }

    @Test
    void listSucceedsForAManagerGroupsScope() throws Exception {
        when(auditTrail.findRecent(anyInt())).thenReturn(List.of());

        MockMvc mockMvc = mockMvcFor(currentUserWithAuthority(VisibilityScope.groups(Set.of(GroupId.random())),
                Set.of(Capability.MANAGE_ORG)), auditTrail, assetDirectoryService);

        mockMvc.perform(get("/api/audit"))
                .andExpect(status().isOk());
    }

    @Test
    void listReturns403ForAPilotAssignedAssetsScope() throws Exception {
        MockMvc mockMvc = mockMvcFor(currentUserWithAuthority(VisibilityScope.assignedAssets(Set.of(AssetId.random())),
                Set.of()), auditTrail, assetDirectoryService);

        mockMvc.perform(get("/api/audit"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verifyNoInteractions(auditTrail);
    }

    @Test
    void listReturns403ForAnUnaffiliatedEmptyScope() throws Exception {
        MockMvc mockMvc = mockMvcFor(currentUserWithAuthority(VisibilityScope.assignedAssets(Set.of()), Set.of()),
                auditTrail, assetDirectoryService);

        mockMvc.perform(get("/api/audit"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verifyNoInteractions(auditTrail);
    }

    @Test
    void listReturns403ForAGroupsScopeMissingTheManageOrgCapability() throws Exception {
        // A VIEWER holds a GROUPS scope (wave B6) but no MANAGE_ORG capability -- must still be
        // refused, exactly the safety property DefaultScopeResolver's VIEWER flip depends on.
        MockMvc mockMvc = mockMvcFor(currentUserWithAuthority(VisibilityScope.groups(Set.of(GroupId.random())),
                Set.of()), auditTrail, assetDirectoryService);

        mockMvc.perform(get("/api/audit"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verifyNoInteractions(auditTrail);
    }

    @Test
    void listReturns400ForANonPositiveLimitOnceAdmitted() throws Exception {
        MockMvc mockMvc = mockMvcFor(currentUserWithAuthority(VisibilityScope.unbounded(), Authority.full()
                .capabilities()), auditTrail, assetDirectoryService);

        mockMvc.perform(get("/api/audit").param("limit", "0"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(auditTrail);
    }

    @Test
    void listReturns400WhenOnlyOneOfTargetTypeOrTargetIdIsSupplied() throws Exception {
        MockMvc mockMvc = mockMvcFor(currentUserWithAuthority(VisibilityScope.unbounded(), Authority.full()
                .capabilities()), auditTrail, assetDirectoryService);

        mockMvc.perform(get("/api/audit").param("targetType", "ASSET"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(auditTrail);
    }

    // -- D18a subtree filter ------------------------------------------------------------------

    @Test
    void listFiltersAnAssetEntryOutsideAManagersSubtree() throws Exception {
        GroupId inScopeGroup = GroupId.random();
        Asset inScopeAsset = assetWithOwnership(new Ownership(UserId.random(), inScopeGroup));
        Asset outOfScopeAsset = assetWithOwnership(new Ownership(UserId.random(), GroupId.random()));
        AuditEntry inScopeEntry = AuditEntry.of(UserId.random(), AuditAction.CREATED, AuditTargetType.ASSET,
                inScopeAsset.id().value().toString(), "in-scope asset");
        AuditEntry outOfScopeEntry = AuditEntry.of(UserId.random(), AuditAction.CREATED, AuditTargetType.ASSET,
                outOfScopeAsset.id().value().toString(), "out-of-scope asset");
        when(auditTrail.findRecent(anyInt())).thenReturn(List.of(inScopeEntry, outOfScopeEntry));
        when(assetDirectoryService.find(inScopeAsset.id())).thenReturn(Optional.of(inScopeAsset));
        when(assetDirectoryService.find(outOfScopeAsset.id())).thenReturn(Optional.of(outOfScopeAsset));

        MockMvc mockMvc = mockMvcFor(currentUserWithAuthority(VisibilityScope.groups(Set.of(inScopeGroup)),
                Set.of(Capability.MANAGE_ORG)), auditTrail, assetDirectoryService);

        mockMvc.perform(get("/api/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].summary").value("in-scope asset"));
    }

    @Test
    void listFiltersADeviceEntryByItsOwningAssetsSubtree() throws Exception {
        GroupId inScopeGroup = GroupId.random();
        DeviceId deviceId = DeviceId.random();
        Asset outOfScopeAsset = assetWithOwnership(new Ownership(UserId.random(), GroupId.random()));
        AuditEntry entry = AuditEntry.of(UserId.random(), AuditAction.UPDATED, AuditTargetType.DEVICE,
                deviceId.value().toString(), "device edited");
        when(auditTrail.findRecent(anyInt())).thenReturn(List.of(entry));
        when(assetDirectoryService.findByDevice(deviceId)).thenReturn(Optional.of(outOfScopeAsset));

        MockMvc mockMvc = mockMvcFor(currentUserWithAuthority(VisibilityScope.groups(Set.of(inScopeGroup)),
                Set.of(Capability.MANAGE_ORG)), auditTrail, assetDirectoryService);

        mockMvc.perform(get("/api/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listFiltersAGroupEntryOutsideAManagersSubtree() throws Exception {
        GroupId inScopeGroup = GroupId.random();
        GroupId outOfScopeGroup = GroupId.random();
        AuditEntry inScopeEntry = AuditEntry.of(UserId.random(), AuditAction.CREATED, AuditTargetType.GROUP,
                inScopeGroup.value().toString(), "in-scope group");
        AuditEntry outOfScopeEntry = AuditEntry.of(UserId.random(), AuditAction.CREATED, AuditTargetType.GROUP,
                outOfScopeGroup.value().toString(), "out-of-scope group");
        when(auditTrail.findRecent(anyInt())).thenReturn(List.of(inScopeEntry, outOfScopeEntry));

        MockMvc mockMvc = mockMvcFor(currentUserWithAuthority(VisibilityScope.groups(Set.of(inScopeGroup)),
                Set.of(Capability.MANAGE_ORG)), auditTrail, assetDirectoryService);

        mockMvc.perform(get("/api/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].summary").value("in-scope group"));
    }

    @Test
    void listNeverFiltersUserAssignmentDatasetOrModelEntriesForAManager() throws Exception {
        AuditEntry userEntry = AuditEntry.of(UserId.random(), AuditAction.CREATED, AuditTargetType.USER,
                UserId.random().value().toString(), "user created");
        AuditEntry assignmentEntry = AuditEntry.of(UserId.random(), AuditAction.CREATED, AuditTargetType.ASSIGNMENT,
                AssetId.random().value().toString(), "assignment granted");
        AuditEntry datasetEntry = AuditEntry.of(UserId.random(), AuditAction.CREATED, AuditTargetType.DATASET,
                "dataset-1", "dataset created");
        AuditEntry modelEntry = AuditEntry.of(UserId.random(), AuditAction.CREATED, AuditTargetType.MODEL,
                "model-1", "model promoted");
        when(auditTrail.findRecent(anyInt()))
                .thenReturn(List.of(userEntry, assignmentEntry, datasetEntry, modelEntry));

        MockMvc mockMvc = mockMvcFor(currentUserWithAuthority(VisibilityScope.groups(Set.of(GroupId.random())),
                Set.of(Capability.MANAGE_ORG)), auditTrail, assetDirectoryService);

        mockMvc.perform(get("/api/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));
        verifyNoInteractions(assetDirectoryService);
    }

    @Test
    void listTreatsAnUnresolvableAssetOrDeviceAsOutOfScope() throws Exception {
        AuditEntry entry = AuditEntry.of(UserId.random(), AuditAction.DELETED, AuditTargetType.ASSET,
                AssetId.random().value().toString(), "asset since removed");
        when(auditTrail.findRecent(anyInt())).thenReturn(List.of(entry));
        when(assetDirectoryService.find(any())).thenReturn(Optional.empty());

        MockMvc mockMvc = mockMvcFor(currentUserWithAuthority(VisibilityScope.groups(Set.of(GroupId.random())),
                Set.of(Capability.MANAGE_ORG)), auditTrail, assetDirectoryService);

        mockMvc.perform(get("/api/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}

package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.security.PrincipalResolver;
import com.drones.vision.application.map.MapAccessPolicy;
import com.drones.vision.application.scope.VisibilityScope;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.identity.domain.model.AuditAction;
import com.drones.vision.identity.domain.model.AuditEntry;
import com.drones.vision.identity.domain.model.AuditTargetType;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.kernel.UserId;
import com.drones.vision.identity.domain.port.AuditTrailPort;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests for {@link AuditController} (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2's
 * ADMIN/MANAGER management gate on the fleet-wide audit trail). Verifies the {@code
 * canManageOrg()} admission check the controller performs directly (there is no application
 * service to put it in — see the class javadoc), plus its pre-existing wiring/validation behavior.
 */
class AuditControllerTest {

    private final AuditTrailPort auditTrail = mock(AuditTrailPort.class);

    /**
     * A {@link CurrentUser} whose {@link CurrentUser#scope()} is exactly {@code scope} —
     * unlike {@code new CurrentUser(Ownership)}, which always resolves to an unbounded scope, this
     * lets a test exercise the MANAGER/PILOT/unaffiliated cases {@link AuditController} gates on.
     * Every other {@link PrincipalResolver} answer is a fixed, unused-by-this-controller stand-in.
     */
    private static CurrentUser currentUserWithScope(VisibilityScope scope) {
        Ownership ownership = new Ownership(UserId.random(), GroupId.random());
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
        });
    }

    private static MockMvc mockMvcFor(CurrentUser currentUser, AuditTrailPort auditTrail) {
        return MockMvcBuilders.standaloneSetup(new AuditController(auditTrail, currentUser))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void listSucceedsForAnAdminUnboundedScope() throws Exception {
        AuditEntry entry = AuditEntry.of(UserId.random(), AuditAction.CREATED, AuditTargetType.ASSET,
                AssetId.random().value().toString(), "created asset");
        when(auditTrail.findRecent(anyInt())).thenReturn(List.of(entry));

        MockMvc mockMvc = mockMvcFor(currentUserWithScope(VisibilityScope.unbounded()), auditTrail);

        mockMvc.perform(get("/api/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].summary").value("created asset"));
    }

    @Test
    void listSucceedsForAManagerGroupsScope() throws Exception {
        when(auditTrail.findRecent(anyInt())).thenReturn(List.of());

        MockMvc mockMvc = mockMvcFor(
                currentUserWithScope(VisibilityScope.groups(Set.of(GroupId.random()))), auditTrail);

        mockMvc.perform(get("/api/audit"))
                .andExpect(status().isOk());
    }

    @Test
    void listReturns403ForAPilotAssignedAssetsScope() throws Exception {
        MockMvc mockMvc = mockMvcFor(
                currentUserWithScope(VisibilityScope.assignedAssets(Set.of(AssetId.random()))), auditTrail);

        mockMvc.perform(get("/api/audit"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verifyNoInteractions(auditTrail);
    }

    @Test
    void listReturns403ForAnUnaffiliatedEmptyScope() throws Exception {
        MockMvc mockMvc = mockMvcFor(
                currentUserWithScope(VisibilityScope.assignedAssets(Set.of())), auditTrail);

        mockMvc.perform(get("/api/audit"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verifyNoInteractions(auditTrail);
    }

    @Test
    void listReturns400ForANonPositiveLimitOnceAdmitted() throws Exception {
        MockMvc mockMvc = mockMvcFor(currentUserWithScope(VisibilityScope.unbounded()), auditTrail);

        mockMvc.perform(get("/api/audit").param("limit", "0"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(auditTrail);
    }

    @Test
    void listReturns400WhenOnlyOneOfTargetTypeOrTargetIdIsSupplied() throws Exception {
        MockMvc mockMvc = mockMvcFor(currentUserWithScope(VisibilityScope.unbounded()), auditTrail);

        mockMvc.perform(get("/api/audit").param("targetType", "ASSET"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(auditTrail);
    }
}

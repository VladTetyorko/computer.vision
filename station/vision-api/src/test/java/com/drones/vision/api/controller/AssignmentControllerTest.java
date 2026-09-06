package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.identity.application.AuthService;
import com.drones.vision.identity.domain.model.Assignment;
import com.drones.vision.identity.domain.model.AssignmentRole;
import com.drones.vision.identity.domain.model.User;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.identity.application.AssignmentService;
import com.drones.vision.platform.Authority;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.identity.domain.port.AssignmentRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import com.drones.vision.api.security.CurrentUser;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests for {@link AssignmentController} (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2,
 * feature 2), seams mocked, with {@link ApiExceptionHandler} registered so the {@code 403}/{@code
 * 404} mappings are exercised. {@link CurrentUser} is built from a plain {@link Ownership}, so its
 * {@code scope()} is unbounded — the assertions here focus on wiring/status, not scope filtering
 * (which is covered end-to-end in {@code ScopedAssetReadAuthEnabledTest}, vision-app).
 */
class AssignmentControllerTest {

    private final AssignmentService assignmentService = mock(AssignmentService.class);
    private final AssignmentRepositoryPort assignmentRepository = mock(AssignmentRepositoryPort.class);
    private final AssetService assetService = mock(AssetService.class);
    private final AuthService authService = mock(AuthService.class);

    private final UserId actor = UserId.random();
    private final CurrentUser currentUser = new CurrentUser(new Ownership(actor, com.drones.vision.kernel.GroupId.random()));

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new AssignmentController(assignmentService, assignmentRepository, assetService,
                    authService, currentUser))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    private final String assetId = AssetId.random().value().toString();
    private final String pilotId = UserId.random().value().toString();

    @Test
    void assignWithNoBodyDefaultsToPilotSeatAndAttributesTheActor() throws Exception {
        mockMvc.perform(put("/api/assets/{a}/pilots/{u}", assetId, pilotId))
                .andExpect(status().isNoContent());
        verify(assignmentService).assign(eq(UserId.of(pilotId)), eq(AssetId.of(assetId)), eq(AssignmentRole.PILOT),
                eq(actor), any(Authority.class));
    }

    @Test
    void assignWithCrewBodyGrantsTheCrewSeat() throws Exception {
        mockMvc.perform(put("/api/assets/{a}/pilots/{u}", assetId, pilotId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"CREW\"}"))
                .andExpect(status().isNoContent());
        verify(assignmentService).assign(eq(UserId.of(pilotId)), eq(AssetId.of(assetId)), eq(AssignmentRole.CREW),
                eq(actor), any(Authority.class));
    }

    @Test
    void unassignReturns204AndCallsService() throws Exception {
        mockMvc.perform(delete("/api/assets/{a}/pilots/{u}", assetId, pilotId))
                .andExpect(status().isNoContent());
        verify(assignmentService).unassign(eq(UserId.of(pilotId)), eq(AssetId.of(assetId)), eq(actor),
                any(Authority.class));
    }

    @Test
    void assignOutOfScopeMapsTo403() throws Exception {
        doThrow(new AccessDeniedException("out of scope"))
                .when(assignmentService).assign(any(), any(), any(), any(), any());
        mockMvc.perform(put("/api/assets/{a}/pilots/{u}", assetId, pilotId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void assignUnknownAssetMapsTo404() throws Exception {
        doThrow(new NoSuchElementException("unknown asset"))
                .when(assignmentService).assign(any(), any(), any(), any(), any());
        mockMvc.perform(put("/api/assets/{a}/pilots/{u}", assetId, pilotId))
                .andExpect(status().isNotFound());
    }

    @Test
    void pilotsListsAssignedPilotsWithTheirSeatAndNamesWhenInScope() throws Exception {
        UserId one = UserId.random();
        when(assignmentRepository.assignmentsForAsset(AssetId.of(assetId)))
                .thenReturn(List.of(new Assignment(one, AssetId.of(assetId), AssignmentRole.CREW)));
        when(authService.find(one))
                .thenReturn(Optional.of(new User(one, "anna", "Anna Kovalenko", "anna@vision.local", "hash", true)));

        mockMvc.perform(get("/api/assets/{a}/pilots", assetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(one.value().toString()))
                .andExpect(jsonPath("$[0].role").value("CREW"))
                .andExpect(jsonPath("$[0].username").value("anna"))
                .andExpect(jsonPath("$[0].displayName").value("Anna Kovalenko"));
    }

    /**
     * The names are resolved by id, not by listing — so a caller whose {@code UserService#list} would
     * answer empty (a pilot's {@code ASSIGNED_ASSETS} scope) still reads a name here
     * (docs/plans/active/INVENTORY-REWORK-PLAN.md D3). This asserts the mechanism: nothing but
     * {@link AuthService#find} is consulted.
     */
    @Test
    void pilotsResolvesNamesByIdWithoutListingUsers() throws Exception {
        UserId one = UserId.random();
        when(assignmentRepository.assignmentsForAsset(AssetId.of(assetId)))
                .thenReturn(List.of(new Assignment(one, AssetId.of(assetId), AssignmentRole.PILOT)));
        when(authService.find(one))
                .thenReturn(Optional.of(new User(one, "bohdan", "Bohdan", "b@vision.local", "hash", true)));

        mockMvc.perform(get("/api/assets/{a}/pilots", assetId)).andExpect(status().isOk());

        verify(authService).find(one);
    }

    @Test
    void pilotsOmitsBothNamesWhenTheUserRecordNoLongerResolves() throws Exception {
        UserId gone = UserId.random();
        when(assignmentRepository.assignmentsForAsset(AssetId.of(assetId)))
                .thenReturn(List.of(new Assignment(gone, AssetId.of(assetId), AssignmentRole.PILOT)));
        when(authService.find(gone)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/assets/{a}/pilots", assetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(gone.value().toString()))
                .andExpect(jsonPath("$[0].username").doesNotExist())
                .andExpect(jsonPath("$[0].displayName").doesNotExist());
    }

    @Test
    void pilotsOutOfScopeAssetIs404() throws Exception {
        when(assetService.details(any(VisibilityScope.class), eq(AssetId.of(assetId))))
                .thenThrow(new NoSuchElementException("out of scope"));
        mockMvc.perform(get("/api/assets/{a}/pilots", assetId))
                .andExpect(status().isNotFound());
    }

    @Test
    void myAssignmentsListsTheCallersAssetsWithTheirSeat() throws Exception {
        AssetId a = AssetId.random();
        when(assignmentService.assignmentsFor(actor)).thenReturn(java.util.Set.of(a));
        when(assignmentRepository.roleFor(actor, a)).thenReturn(Optional.of(AssignmentRole.CREW));

        mockMvc.perform(get("/api/me/assignments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].assetId").value(a.value().toString()))
                .andExpect(jsonPath("$[0].role").value("CREW"));
    }

    @Test
    void myAssignmentsDefaultsToPilotWhenRoleForFindsNoLink() throws Exception {
        AssetId a = AssetId.random();
        when(assignmentService.assignmentsFor(actor)).thenReturn(java.util.Set.of(a));
        when(assignmentRepository.roleFor(actor, a)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/me/assignments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("PILOT"));
    }
}

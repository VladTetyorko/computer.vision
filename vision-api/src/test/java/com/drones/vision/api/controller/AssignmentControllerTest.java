package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.application.scope.AccessDeniedException;
import com.drones.vision.application.asset.AssetService;
import com.drones.vision.application.identity.AssignmentService;
import com.drones.vision.application.scope.VisibilityScope;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.identity.domain.port.AssignmentRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.NoSuchElementException;
import java.util.Set;
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

    private final UserId actor = UserId.random();
    private final CurrentUser currentUser = new CurrentUser(new Ownership(actor, com.drones.vision.kernel.GroupId.random()));

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new AssignmentController(assignmentService, assignmentRepository, assetService, currentUser))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    private final String assetId = AssetId.random().value().toString();
    private final String pilotId = UserId.random().value().toString();

    @Test
    void assignReturns204AndCallsService() throws Exception {
        mockMvc.perform(put("/api/assets/{a}/pilots/{u}", assetId, pilotId))
                .andExpect(status().isNoContent());
        verify(assignmentService).assign(eq(UserId.of(pilotId)), eq(AssetId.of(assetId)), any(VisibilityScope.class));
    }

    @Test
    void unassignReturns204AndCallsService() throws Exception {
        mockMvc.perform(delete("/api/assets/{a}/pilots/{u}", assetId, pilotId))
                .andExpect(status().isNoContent());
        verify(assignmentService).unassign(eq(UserId.of(pilotId)), eq(AssetId.of(assetId)), any(VisibilityScope.class));
    }

    @Test
    void assignOutOfScopeMapsTo403() throws Exception {
        doThrow(new AccessDeniedException("out of scope"))
                .when(assignmentService).assign(any(), any(), any());
        mockMvc.perform(put("/api/assets/{a}/pilots/{u}", assetId, pilotId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void assignUnknownAssetMapsTo404() throws Exception {
        doThrow(new NoSuchElementException("unknown asset"))
                .when(assignmentService).assign(any(), any(), any());
        mockMvc.perform(put("/api/assets/{a}/pilots/{u}", assetId, pilotId))
                .andExpect(status().isNotFound());
    }

    @Test
    void pilotsListsAssignedPilotsWhenInScope() throws Exception {
        UserId one = UserId.random();
        when(assignmentRepository.pilotsForAsset(AssetId.of(assetId))).thenReturn(Set.of(one));

        mockMvc.perform(get("/api/assets/{a}/pilots", assetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(one.value().toString()));
    }

    @Test
    void pilotsOutOfScopeAssetIs404() throws Exception {
        when(assetService.details(any(VisibilityScope.class), eq(AssetId.of(assetId))))
                .thenThrow(new NoSuchElementException("out of scope"));
        mockMvc.perform(get("/api/assets/{a}/pilots", assetId))
                .andExpect(status().isNotFound());
    }

    @Test
    void myAssignmentsListsTheCallersAssets() throws Exception {
        AssetId a = AssetId.random();
        when(assignmentService.assignmentsFor(actor)).thenReturn(Set.of(a));

        mockMvc.perform(get("/api/me/assignments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].assetId").value(a.value().toString()));
    }
}

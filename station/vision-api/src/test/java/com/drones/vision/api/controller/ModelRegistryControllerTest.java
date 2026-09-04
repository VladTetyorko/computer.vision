package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.learning.application.ModelRegistryService;
import com.drones.vision.learning.application.PromotionResult;
import com.drones.vision.learning.domain.model.ModelStatus;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.drones.vision.api.security.CurrentUser;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for {@link ModelRegistryController} (docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase 2 T9;
 * folded/widened per docs/plans/active/CV-SETTINGS-PLAN.md §3.2/§5.2), mirroring {@link
 * DatasetControllerTest}'s style: a standalone {@code MockMvc} over a mocked {@link
 * ModelRegistryService} collaborator, with {@link ApiExceptionHandler} attached so error mapping is
 * exercised exactly as it runs in production.
 *
 * <p>{@code GET /api/cv/registry/models} no longer exists on this controller — see {@link
 * CvModelsControllerTest} for its replacement, {@code GET /api/cv/models}.
 */
class ModelRegistryControllerTest {

    private ModelRegistryService modelRegistryService;
    private MockMvc mockMvc;

    private final UserId ownerId = UserId.random();
    private final Ownership ownership = new Ownership(ownerId, GroupId.random());
    private final CurrentUser currentUser = new CurrentUser(ownership);

    @BeforeEach
    void setUp() {
        modelRegistryService = mock(ModelRegistryService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ModelRegistryController(modelRegistryService, currentUser))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    // ---- POST /api/cv/registry/models/{id}/promote ----

    @Test
    void promoteReturns200WithThePromotionOutcomeAndThreadsActorAndScope() throws Exception {
        when(modelRegistryService.promote("yolo26n.pt", "v3", ownerId, currentUser.authority()))
                .thenReturn(new PromotionResult("yolo26n.pt", "v3", ModelStatus.LIVE, "yolo26n.pt", "v2"));

        mockMvc.perform(post("/api/cv/registry/models/{id}/promote", "yolo26n.pt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": \"v3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("yolo26n.pt"))
                .andExpect(jsonPath("$.version").value("v3"))
                .andExpect(jsonPath("$.status").value("LIVE"))
                .andExpect(jsonPath("$.previousModelId").value("yolo26n.pt"))
                .andExpect(jsonPath("$.previousVersion").value("v2"));

        verify(modelRegistryService).promote("yolo26n.pt", "v3", ownerId, currentUser.authority());
    }

    @Test
    void promoteOmitsThePreviousFieldsWhenNothingWasDemoted() throws Exception {
        when(modelRegistryService.promote("yolo26n.pt", "v3", ownerId, currentUser.authority()))
                .thenReturn(new PromotionResult("yolo26n.pt", "v3", ModelStatus.LIVE, null, null));

        mockMvc.perform(post("/api/cv/registry/models/{id}/promote", "yolo26n.pt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": \"v3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousModelId").doesNotExist())
                .andExpect(jsonPath("$.previousVersion").doesNotExist());
    }

    @Test
    void promoteReturns400ForABlankVersion() throws Exception {
        when(modelRegistryService.promote(eq("yolo26n.pt"), eq(""), eq(ownerId), any()))
                .thenThrow(new IllegalArgumentException("ModelRef version must not be blank"));

        mockMvc.perform(post("/api/cv/registry/models/{id}/promote", "yolo26n.pt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void promoteWithNoVersionFieldReturns400() throws Exception {
        when(modelRegistryService.promote(eq("yolo26n.pt"), eq((String) null), eq(ownerId), any()))
                .thenThrow(new IllegalArgumentException("ModelRef version must not be blank"));

        mockMvc.perform(post("/api/cv/registry/models/{id}/promote", "yolo26n.pt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void promoteReturns403WhenCallerMayNotAdministerTheOrganization() throws Exception {
        doThrow(new AccessDeniedException("Not permitted to promote models"))
                .when(modelRegistryService).promote(eq("yolo26n.pt"), eq("v3"), eq(ownerId), any());

        mockMvc.perform(post("/api/cv/registry/models/{id}/promote", "yolo26n.pt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": \"v3\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void promoteReturns409WhenCvServiceRefuses() throws Exception {
        doThrow(new IllegalStateException("cv-service refused to promote model yolo26n.pt:v3: unknown id"))
                .when(modelRegistryService).promote(eq("yolo26n.pt"), eq("v3"), eq(ownerId), any());

        mockMvc.perform(post("/api/cv/registry/models/{id}/promote", "yolo26n.pt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": \"v3\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    // ---- POST /api/cv/registry/rollback ----

    @Test
    void rollbackReturns200WithTheRestoredModelAndThreadsActorAndScope() throws Exception {
        when(modelRegistryService.rollback(ownerId, currentUser.authority()))
                .thenReturn(new PromotionResult("yolo26n.pt", "v2", ModelStatus.LIVE, "yolo26n.pt", "v3"));

        mockMvc.perform(post("/api/cv/registry/rollback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("yolo26n.pt"))
                .andExpect(jsonPath("$.version").value("v2"))
                .andExpect(jsonPath("$.status").value("LIVE"))
                .andExpect(jsonPath("$.previousModelId").value("yolo26n.pt"))
                .andExpect(jsonPath("$.previousVersion").value("v3"));

        verify(modelRegistryService).rollback(ownerId, currentUser.authority());
    }

    @Test
    void rollbackReturns403WhenCallerMayNotAdministerTheOrganization() throws Exception {
        doThrow(new AccessDeniedException("Not permitted to roll back models"))
                .when(modelRegistryService).rollback(eq(ownerId), any());

        mockMvc.perform(post("/api/cv/registry/rollback"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void rollbackReturns409WhenNoPreviousModelExists() throws Exception {
        doThrow(new IllegalStateException("No previous model to roll back to"))
                .when(modelRegistryService).rollback(eq(ownerId), any());

        mockMvc.perform(post("/api/cv/registry/rollback"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }
}

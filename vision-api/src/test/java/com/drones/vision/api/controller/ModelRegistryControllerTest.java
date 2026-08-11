package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.identity.application.scope.AccessDeniedException;
import com.drones.vision.learning.application.ModelRegistryService;
import com.drones.vision.learning.application.RegisteredModel;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import com.drones.vision.api.security.CurrentUser;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for {@link ModelRegistryController} (docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase 2 T9),
 * mirroring {@link DatasetControllerTest}'s style: a standalone {@code MockMvc} over a mocked
 * {@link ModelRegistryService} collaborator, with {@link ApiExceptionHandler} attached so error
 * mapping is exercised exactly as it runs in production.
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

    // ---- GET /api/cv/registry/models ----

    @Test
    void modelsReturns200WithTheRegistrySnapshotMarkingTheActiveOne() throws Exception {
        when(modelRegistryService.models()).thenReturn(List.of(
                new RegisteredModel(new ModelRef("yolo26n.pt", "v3"), true),
                new RegisteredModel(new ModelRef("yolo26n.pt", "v2"), false)));

        mockMvc.perform(get("/api/cv/registry/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models", hasSize(2)))
                .andExpect(jsonPath("$.models[0].id").value("yolo26n.pt"))
                .andExpect(jsonPath("$.models[0].version").value("v3"))
                .andExpect(jsonPath("$.models[0].active").value(true))
                .andExpect(jsonPath("$.models[1].version").value("v2"))
                .andExpect(jsonPath("$.models[1].active").value(false));
    }

    @Test
    void modelsReturns200WithAnEmptyListWhenNoneKnown() throws Exception {
        when(modelRegistryService.models()).thenReturn(List.of());

        mockMvc.perform(get("/api/cv/registry/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models", hasSize(0)));
    }

    // ---- POST /api/cv/registry/models/{id}/promote ----

    @Test
    void promoteReturns200WithThePromotedReferenceAndThreadsActorAndScope() throws Exception {
        mockMvc.perform(post("/api/cv/registry/models/{id}/promote", "yolo26n.pt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": \"v3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("yolo26n.pt"))
                .andExpect(jsonPath("$.version").value("v3"))
                .andExpect(jsonPath("$.active").value(true));

        verify(modelRegistryService).promote(new ModelRef("yolo26n.pt", "v3"), ownerId, currentUser.scope());
    }

    @Test
    void promoteReturns400ForABlankVersion() throws Exception {
        mockMvc.perform(post("/api/cv/registry/models/{id}/promote", "yolo26n.pt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void promoteReturns403WhenCallerMayNotManageTheOrganization() throws Exception {
        doThrow(new AccessDeniedException("Not permitted to promote models"))
                .when(modelRegistryService).promote(eq(new ModelRef("yolo26n.pt", "v3")), eq(ownerId), any());

        mockMvc.perform(post("/api/cv/registry/models/{id}/promote", "yolo26n.pt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": \"v3\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void promoteReturns409WhenCvServiceRefuses() throws Exception {
        doThrow(new IllegalStateException("cv-service refused to promote model yolo26n.pt:v3: unknown id"))
                .when(modelRegistryService).promote(eq(new ModelRef("yolo26n.pt", "v3")), eq(ownerId), any());

        mockMvc.perform(post("/api/cv/registry/models/{id}/promote", "yolo26n.pt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": \"v3\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void promoteWithNoVersionFieldReturns400() throws Exception {
        mockMvc.perform(post("/api/cv/registry/models/{id}/promote", "yolo26n.pt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}

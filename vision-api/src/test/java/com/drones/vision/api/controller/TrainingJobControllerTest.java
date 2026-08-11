package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.identity.application.scope.AccessDeniedException;
import com.drones.vision.learning.application.TrainingJobService;
import com.drones.vision.learning.application.TrainingJobView;
import com.drones.vision.identity.application.scope.VisibilityScope;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.learning.domain.model.JobState;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.learning.domain.model.TrainingJobSpec;
import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import com.drones.vision.api.security.CurrentUser;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for {@link TrainingJobController} (docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase 2's last
 * backend wave), mirroring {@link ModelRegistryControllerTest}'s style: a standalone {@code
 * MockMvc} over a mocked {@link TrainingJobService} collaborator, with {@link ApiExceptionHandler}
 * attached so error mapping is exercised exactly as it runs in production.
 */
class TrainingJobControllerTest {

    private TrainingJobService trainingJobService;
    private MockMvc mockMvc;

    private final UserId ownerId = UserId.random();
    private final Ownership ownership = new Ownership(ownerId, GroupId.random());
    private final CurrentUser currentUser = new CurrentUser(ownership);

    @BeforeEach
    void setUp() {
        trainingJobService = mock(TrainingJobService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TrainingJobController(trainingJobService, currentUser))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static TrainingJobView view(String jobId, String datasetId, JobState state, String message) {
        return new TrainingJobView(jobId, "yolo26n.pt", datasetId, 50, 3, 50, 0.42, 0.81, state, message,
                Instant.parse("2026-08-01T10:00:00Z"));
    }

    // ---- POST /api/datasets/{id}/train ----

    @Test
    void startReturns202WithTheFreshJobAndThreadsActorAndScope() throws Exception {
        String datasetId = "11111111-1111-1111-1111-111111111111";
        when(trainingJobService.start(any(TrainingJobSpec.class), eq(ownerId), any(VisibilityScope.class)))
                .thenReturn("job-1");
        when(trainingJobService.job("job-1"))
                .thenReturn(Optional.of(view("job-1", datasetId, JobState.RUNNING, "")));

        mockMvc.perform(post("/api/datasets/{id}/train", datasetId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseModel\": \"yolo26n.pt\", \"epochs\": 50}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-1"))
                .andExpect(jsonPath("$.baseModel").value("yolo26n.pt"))
                .andExpect(jsonPath("$.datasetId").value(datasetId))
                .andExpect(jsonPath("$.epochs").value(50))
                .andExpect(jsonPath("$.epoch").value(3))
                .andExpect(jsonPath("$.totalEpochs").value(50))
                .andExpect(jsonPath("$.loss").value(0.42))
                .andExpect(jsonPath("$.map50").value(0.81))
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.message").value(""))
                .andExpect(jsonPath("$.startedAt").exists());

        ArgumentCaptor<TrainingJobSpec> captor = ArgumentCaptor.forClass(TrainingJobSpec.class);
        verify(trainingJobService).start(captor.capture(), eq(ownerId), eq(currentUser.scope()));
        assertEquals("yolo26n.pt", captor.getValue().baseModel());
        assertEquals(datasetId, captor.getValue().datasetId());
        assertEquals(50, captor.getValue().epochs());
    }

    @Test
    void startReturns400ForABlankBaseModel() throws Exception {
        mockMvc.perform(post("/api/datasets/{id}/train", "11111111-1111-1111-1111-111111111111")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseModel\": \"\", \"epochs\": 50}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void startReturns400ForNonPositiveEpochs() throws Exception {
        mockMvc.perform(post("/api/datasets/{id}/train", "11111111-1111-1111-1111-111111111111")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseModel\": \"yolo26n.pt\", \"epochs\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void startReturns400ForAMalformedDatasetId() throws Exception {
        mockMvc.perform(post("/api/datasets/{id}/train", "not-a-uuid").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseModel\": \"yolo26n.pt\", \"epochs\": 50}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void startReturns404ForAnUnknownDataset() throws Exception {
        String datasetId = "11111111-1111-1111-1111-111111111111";
        when(trainingJobService.start(any(TrainingJobSpec.class), eq(ownerId), any(VisibilityScope.class)))
                .thenThrow(new java.util.NoSuchElementException("Unknown dataset: " + datasetId));

        mockMvc.perform(post("/api/datasets/{id}/train", datasetId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseModel\": \"yolo26n.pt\", \"epochs\": 50}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void startReturns400WhenDatasetHasNoLabeledSamples() throws Exception {
        String datasetId = "11111111-1111-1111-1111-111111111111";
        when(trainingJobService.start(any(TrainingJobSpec.class), eq(ownerId), any(VisibilityScope.class)))
                .thenThrow(new IllegalArgumentException("Dataset " + datasetId + " has no LABELED samples to train on"));

        mockMvc.perform(post("/api/datasets/{id}/train", datasetId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseModel\": \"yolo26n.pt\", \"epochs\": 50}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void startReturns403WhenCallerMayNotManageTheOrganization() throws Exception {
        when(trainingJobService.start(any(TrainingJobSpec.class), eq(ownerId), any(VisibilityScope.class)))
                .thenThrow(new AccessDeniedException("Not permitted to start training jobs"));

        mockMvc.perform(post("/api/datasets/{id}/train", "11111111-1111-1111-1111-111111111111")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseModel\": \"yolo26n.pt\", \"epochs\": 50}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    // ---- GET /api/training/jobs/{jobId} ----

    @Test
    void jobReturns200WithTheJobsLatestState() throws Exception {
        when(trainingJobService.job("job-1"))
                .thenReturn(Optional.of(view("job-1", "dataset-1", JobState.SUCCEEDED, "yolo26n-v4.pt")));

        mockMvc.perform(get("/api/training/jobs/{jobId}", "job-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-1"))
                .andExpect(jsonPath("$.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.message").value("yolo26n-v4.pt"));
    }

    @Test
    void jobReturns200ForAFailedJobRatherThanAnError() throws Exception {
        when(trainingJobService.job("job-1"))
                .thenReturn(Optional.of(view("job-1", "dataset-1", JobState.FAILED, "transport error")));

        mockMvc.perform(get("/api/training/jobs/{jobId}", "job-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.message").value("transport error"));
    }

    @Test
    void jobReturns404ForAnUnknownJobId() throws Exception {
        when(trainingJobService.job("unknown")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/training/jobs/{jobId}", "unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    // ---- GET /api/training/jobs ----

    @Test
    void jobsReturns200WrappedUnderJobs() throws Exception {
        when(trainingJobService.jobs()).thenReturn(List.of(
                view("job-2", "dataset-1", JobState.RUNNING, ""),
                view("job-1", "dataset-1", JobState.SUCCEEDED, "yolo26n-v4.pt")));

        mockMvc.perform(get("/api/training/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs", hasSize(2)))
                .andExpect(jsonPath("$.jobs[0].jobId").value("job-2"))
                .andExpect(jsonPath("$.jobs[1].jobId").value("job-1"));
    }

    @Test
    void jobsReturns200WithEmptyListWhenNoneTracked() throws Exception {
        when(trainingJobService.jobs()).thenReturn(List.of());

        mockMvc.perform(get("/api/training/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs", hasSize(0)));
    }
}

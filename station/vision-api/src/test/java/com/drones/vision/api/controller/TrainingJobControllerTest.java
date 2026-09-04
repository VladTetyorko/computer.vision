package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.learning.application.TrainingJobService;
import com.drones.vision.learning.application.TrainingJobView;
import com.drones.vision.platform.Authority;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.learning.domain.model.Dataset;
import com.drones.vision.learning.domain.model.DatasetId;
import com.drones.vision.learning.domain.model.DatasetStatus;
import com.drones.vision.learning.domain.model.JobState;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.learning.domain.model.TrainingJobSpec;
import com.drones.vision.learning.domain.model.TrainingRunId;
import com.drones.vision.learning.domain.model.TrainingRunRecord;
import com.drones.vision.learning.domain.port.DatasetRepositoryPort;
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
import static org.mockito.ArgumentMatchers.anyInt;
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
    private DatasetRepositoryPort datasetRepositoryPort;
    private MockMvc mockMvc;

    private final UserId ownerId = UserId.random();
    private final Ownership ownership = new Ownership(ownerId, GroupId.random());
    private final CurrentUser currentUser = new CurrentUser(ownership);

    @BeforeEach
    void setUp() {
        trainingJobService = mock(TrainingJobService.class);
        datasetRepositoryPort = mock(DatasetRepositoryPort.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TrainingJobController(trainingJobService, datasetRepositoryPort, currentUser))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static TrainingJobView view(String jobId, String datasetId, JobState state, String message) {
        return new TrainingJobView(jobId, "yolo26n.pt", datasetId, 50, 3, 50, 0.42, 0.81, state, message,
                Instant.parse("2026-08-01T10:00:00Z"));
    }

    private static TrainingRunRecord run(TrainingRunId runId, DatasetId datasetId, JobState state, UserId startedBy) {
        return new TrainingRunRecord(runId, datasetId, "yolo26n.pt", 50, state, 3, 50, 0.42, 0.81,
                state == JobState.SUCCEEDED ? "yolo26n-v4.pt" : null, startedBy,
                Instant.parse("2026-08-01T10:00:00Z"), state == JobState.RUNNING ? null
                        : Instant.parse("2026-08-01T11:00:00Z"), "");
    }

    private static Dataset dataset(DatasetId id, String name) {
        return new Dataset(id, name, null, List.of("person"), new Ownership(UserId.random(), GroupId.random()),
                DatasetStatus.OPEN, Instant.parse("2026-07-01T00:00:00Z"));
    }

    // ---- POST /api/datasets/{id}/train ----

    @Test
    void startReturns202WithTheFreshJobAndThreadsActorAndScope() throws Exception {
        String datasetId = "11111111-1111-1111-1111-111111111111";
        when(trainingJobService.start(any(TrainingJobSpec.class), eq(ownerId), any(Authority.class)))
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
        verify(trainingJobService).start(captor.capture(), eq(ownerId), eq(currentUser.authority()));
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
        when(trainingJobService.start(any(TrainingJobSpec.class), eq(ownerId), any(Authority.class)))
                .thenThrow(new java.util.NoSuchElementException("Unknown dataset: " + datasetId));

        mockMvc.perform(post("/api/datasets/{id}/train", datasetId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseModel\": \"yolo26n.pt\", \"epochs\": 50}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void startReturns400WhenDatasetHasNoLabeledSamples() throws Exception {
        String datasetId = "11111111-1111-1111-1111-111111111111";
        when(trainingJobService.start(any(TrainingJobSpec.class), eq(ownerId), any(Authority.class)))
                .thenThrow(new IllegalArgumentException("Dataset " + datasetId + " has no LABELED samples to train on"));

        mockMvc.perform(post("/api/datasets/{id}/train", datasetId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseModel\": \"yolo26n.pt\", \"epochs\": 50}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void startReturns403WhenCallerMayNotManageTheOrganization() throws Exception {
        when(trainingJobService.start(any(TrainingJobSpec.class), eq(ownerId), any(Authority.class)))
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

    // ---- GET /api/cv/training/runs ----

    @Test
    void runsReturns200WrappedUnderRunsWithResolvedDatasetNameAndThreadsActorAndScope() throws Exception {
        DatasetId datasetId = DatasetId.random();
        TrainingRunId runId = TrainingRunId.random();
        when(trainingJobService.runs(eq(50), eq(ownerId), any(Authority.class)))
                .thenReturn(List.of(run(runId, datasetId, JobState.SUCCEEDED, ownerId)));
        when(datasetRepositoryPort.findById(datasetId)).thenReturn(Optional.of(dataset(datasetId, "my-dataset")));

        mockMvc.perform(get("/api/cv/training/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs", hasSize(1)))
                .andExpect(jsonPath("$.runs[0].runId").value(runId.value().toString()))
                .andExpect(jsonPath("$.runs[0].datasetId").value(datasetId.value().toString()))
                .andExpect(jsonPath("$.runs[0].datasetName").value("my-dataset"))
                .andExpect(jsonPath("$.runs[0].state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.runs[0].loss").value(0.42))
                .andExpect(jsonPath("$.runs[0].map50").value(0.81))
                .andExpect(jsonPath("$.runs[0].outputModelId").value("yolo26n-v4.pt"))
                .andExpect(jsonPath("$.runs[0].startedBy").value(ownerId.value().toString()));

        verify(trainingJobService).runs(50, ownerId, currentUser.authority());
    }

    @Test
    void runsFallsBackToTheDatasetIdWhenTheDatasetIsMissing() throws Exception {
        DatasetId datasetId = DatasetId.random();
        when(trainingJobService.runs(anyInt(), eq(ownerId), any(Authority.class)))
                .thenReturn(List.of(run(TrainingRunId.random(), datasetId, JobState.RUNNING, ownerId)));
        when(datasetRepositoryPort.findById(datasetId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/cv/training/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs[0].datasetName").value(datasetId.value().toString()))
                .andExpect(jsonPath("$.runs[0].finishedAt").doesNotExist());
    }

    @Test
    void runsHonorsAnExplicitLimit() throws Exception {
        when(trainingJobService.runs(anyInt(), eq(ownerId), any(Authority.class))).thenReturn(List.of());

        mockMvc.perform(get("/api/cv/training/runs").param("limit", "10")).andExpect(status().isOk());

        verify(trainingJobService).runs(10, ownerId, currentUser.authority());
    }

    @Test
    void runsReturns403WhenCallerMayNotManageTheOrganization() throws Exception {
        when(trainingJobService.runs(anyInt(), eq(ownerId), any(Authority.class)))
                .thenThrow(new AccessDeniedException("Not permitted to view training runs"));

        mockMvc.perform(get("/api/cv/training/runs"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    // ---- GET /api/cv/training/runs/{runId} ----

    @Test
    void runReturns200WithTheRunsLatestPersistedState() throws Exception {
        DatasetId datasetId = DatasetId.random();
        TrainingRunId runId = TrainingRunId.random();
        when(trainingJobService.run(eq(runId), eq(ownerId), any(Authority.class)))
                .thenReturn(run(runId, datasetId, JobState.FAILED, ownerId));
        when(datasetRepositoryPort.findById(datasetId)).thenReturn(Optional.of(dataset(datasetId, "my-dataset")));

        mockMvc.perform(get("/api/cv/training/runs/{runId}", runId.value().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId.value().toString()))
                .andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.datasetName").value("my-dataset"));
    }

    @Test
    void runReturns404ForAnUnknownRunId() throws Exception {
        TrainingRunId runId = TrainingRunId.random();
        when(trainingJobService.run(eq(runId), eq(ownerId), any(Authority.class)))
                .thenThrow(new java.util.NoSuchElementException("Unknown training run: " + runId.value()));

        mockMvc.perform(get("/api/cv/training/runs/{runId}", runId.value().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void runReturns400ForAMalformedRunId() throws Exception {
        mockMvc.perform(get("/api/cv/training/runs/{runId}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void runReturns403WhenCallerMayNotManageTheOrganization() throws Exception {
        TrainingRunId runId = TrainingRunId.random();
        when(trainingJobService.run(eq(runId), eq(ownerId), any(Authority.class)))
                .thenThrow(new AccessDeniedException("Not permitted to view training runs"));

        mockMvc.perform(get("/api/cv/training/runs/{runId}", runId.value().toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }
}

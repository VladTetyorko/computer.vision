package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.application.scope.AccessDeniedException;
import com.drones.vision.application.training.DatasetService;
import com.drones.vision.application.training.DatasetSpec;
import com.drones.vision.application.scope.VisibilityScope;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.learning.domain.model.Dataset;
import com.drones.vision.learning.domain.model.DatasetId;
import com.drones.vision.learning.domain.model.DatasetStatus;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.learning.domain.model.SampleStatus;
import com.drones.vision.kernel.UserId;
import com.drones.vision.learning.domain.port.TrainingSampleRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import com.drones.vision.api.security.CurrentUser;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for {@link DatasetController} (docs/plans/done/CV-TRAINING-PLAN.md §3's frozen wire
 * contract), mirroring {@link MarksControllerTest}/{@link GeofenceControllerTest}'s style: a
 * standalone {@code MockMvc} over mocked {@link DatasetService}/{@link
 * TrainingSampleRepositoryPort} collaborators, with {@link ApiExceptionHandler} attached so error
 * mapping is exercised exactly as it runs in production.
 */
class DatasetControllerTest {

    private DatasetService datasetService;
    private TrainingSampleRepositoryPort trainingSampleRepositoryPort;
    private MockMvc mockMvc;

    private final UserId ownerId = UserId.random();
    private final Ownership ownership = new Ownership(ownerId, GroupId.random());
    private final CurrentUser currentUser = new CurrentUser(ownership);

    @BeforeEach
    void setUp() {
        datasetService = mock(DatasetService.class);
        trainingSampleRepositoryPort = mock(TrainingSampleRepositoryPort.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new DatasetController(datasetService, trainingSampleRepositoryPort, currentUser))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private Dataset dataset(DatasetId id, String name, CategoryId category, List<String> classes) {
        return new Dataset(id, name, category, classes, ownership, DatasetStatus.OPEN, Instant.now());
    }

    // ---- POST /api/datasets ----

    @Test
    void createReturns201WithSampleCountsAllZeroAndThreadsOwnershipActorAndScope() throws Exception {
        DatasetId created = DatasetId.random();
        when(datasetService.create(any(DatasetSpec.class), eq(ownership), eq(ownerId), any(VisibilityScope.class)))
                .thenReturn(dataset(created, "Buildings", new CategoryId("building"), List.of("building", "tower")));
        when(trainingSampleRepositoryPort.countByDataset(eq(created), any())).thenReturn(0);

        String body = """
                {"name": "Buildings", "targetCategory": "building", "classes": ["building", "tower"]}
                """;

        mockMvc.perform(post("/api/datasets").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(created.value().toString()))
                .andExpect(jsonPath("$.name").value("Buildings"))
                .andExpect(jsonPath("$.targetCategory").value("building"))
                .andExpect(jsonPath("$.classes", hasSize(2)))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.sampleCounts.PENDING").value(0))
                .andExpect(jsonPath("$.sampleCounts.LABELED").value(0))
                .andExpect(jsonPath("$.sampleCounts.DISCARDED").value(0));

        ArgumentCaptor<DatasetSpec> captor = ArgumentCaptor.forClass(DatasetSpec.class);
        verify(datasetService).create(captor.capture(), eq(ownership), eq(ownerId), eq(currentUser.scope()));
        assertEquals("Buildings", captor.getValue().name());
        assertEquals(List.of("building", "tower"), captor.getValue().classes());
    }

    @Test
    void createReturns400ForABlankName() throws Exception {
        mockMvc.perform(post("/api/datasets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"\", \"classes\": []}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void createReturns403WhenCallerMayNotManageTheOrganization() throws Exception {
        when(datasetService.create(any(DatasetSpec.class), eq(ownership), eq(ownerId), any(VisibilityScope.class)))
                .thenThrow(new AccessDeniedException("Not permitted to create datasets"));

        mockMvc.perform(post("/api/datasets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Buildings\", \"classes\": []}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    // ---- GET /api/datasets ----

    @Test
    void listReturns200WrappedUnderDatasetsWithComputedSampleCounts() throws Exception {
        DatasetId first = DatasetId.random();
        DatasetId second = DatasetId.random();
        when(datasetService.list(eq(ownerId), any(VisibilityScope.class))).thenReturn(
                List.of(dataset(first, "A", null, List.of()), dataset(second, "B", null, List.of())));
        when(trainingSampleRepositoryPort.countByDataset(eq(first), eq(SampleStatus.PENDING))).thenReturn(12);
        when(trainingSampleRepositoryPort.countByDataset(eq(first), eq(SampleStatus.LABELED))).thenReturn(40);
        when(trainingSampleRepositoryPort.countByDataset(eq(first), eq(SampleStatus.DISCARDED))).thenReturn(3);

        mockMvc.perform(get("/api/datasets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasets", hasSize(2)))
                .andExpect(jsonPath("$.datasets[0].name").value("A"))
                .andExpect(jsonPath("$.datasets[0].sampleCounts.PENDING").value(12))
                .andExpect(jsonPath("$.datasets[0].sampleCounts.LABELED").value(40))
                .andExpect(jsonPath("$.datasets[0].sampleCounts.DISCARDED").value(3))
                .andExpect(jsonPath("$.datasets[0].targetCategory").doesNotExist())
                .andExpect(jsonPath("$.datasets[1].name").value("B"));
    }

    @Test
    void listReturns200WithEmptyListWhenNoneVisible() throws Exception {
        when(datasetService.list(eq(ownerId), any(VisibilityScope.class))).thenReturn(List.of());

        mockMvc.perform(get("/api/datasets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasets", hasSize(0)));
    }

    // ---- GET /api/datasets/{id} ----

    @Test
    void getReturns200WithMappedDataset() throws Exception {
        DatasetId id = DatasetId.random();
        when(datasetService.get(eq(id), eq(ownerId), any(VisibilityScope.class)))
                .thenReturn(dataset(id, "Buildings", new CategoryId("building"), List.of("building")));
        when(trainingSampleRepositoryPort.countByDataset(eq(id), any())).thenReturn(0);

        mockMvc.perform(get("/api/datasets/{id}", id.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.value().toString()))
                .andExpect(jsonPath("$.name").value("Buildings"));
    }

    @Test
    void getReturns404ForUnknownDatasetId() throws Exception {
        DatasetId id = DatasetId.random();
        when(datasetService.get(eq(id), eq(ownerId), any(VisibilityScope.class)))
                .thenThrow(new NoSuchElementException("Unknown dataset: " + id.value()));

        mockMvc.perform(get("/api/datasets/{id}", id.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void getReturns403ForADatasetOutsideCallersScope() throws Exception {
        DatasetId id = DatasetId.random();
        when(datasetService.get(eq(id), eq(ownerId), any(VisibilityScope.class)))
                .thenThrow(new AccessDeniedException("Dataset " + id.value() + " is outside your scope"));

        mockMvc.perform(get("/api/datasets/{id}", id.value()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void getReturns400ForAMalformedDatasetId() throws Exception {
        mockMvc.perform(get("/api/datasets/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    // ---- DELETE /api/datasets/{id} ----

    @Test
    void deleteReturns204() throws Exception {
        DatasetId id = DatasetId.random();

        mockMvc.perform(delete("/api/datasets/{id}", id.value())).andExpect(status().isNoContent());

        verify(datasetService).delete(id, ownerId, currentUser.scope());
    }

    @Test
    void deleteReturns404ForUnknownDatasetId() throws Exception {
        DatasetId id = DatasetId.random();
        org.mockito.Mockito.doThrow(new NoSuchElementException("Unknown dataset: " + id.value()))
                .when(datasetService).delete(eq(id), eq(ownerId), any());

        mockMvc.perform(delete("/api/datasets/{id}", id.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void deleteReturns403WhenCallerMayNotManageTheOrganization() throws Exception {
        DatasetId id = DatasetId.random();
        org.mockito.Mockito.doThrow(new AccessDeniedException("Not permitted to delete dataset " + id.value()))
                .when(datasetService).delete(eq(id), eq(ownerId), any());

        mockMvc.perform(delete("/api/datasets/{id}", id.value()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }
}

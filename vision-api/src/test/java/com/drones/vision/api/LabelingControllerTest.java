package com.drones.vision.api;

import com.drones.vision.application.AccessDeniedException;
import com.drones.vision.application.CaptureSpec;
import com.drones.vision.application.DatasetService;
import com.drones.vision.application.LabelSpec;
import com.drones.vision.application.LabelingService;
import com.drones.vision.application.VisibilityScope;
import com.drones.vision.domain.model.Annotation;
import com.drones.vision.domain.model.AnnotationSource;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.BoundingBox;
import com.drones.vision.domain.model.Dataset;
import com.drones.vision.domain.model.DatasetExport;
import com.drones.vision.domain.model.DatasetId;
import com.drones.vision.domain.model.DatasetStatus;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.SampleImage;
import com.drones.vision.domain.model.SampleStatus;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.TrainingSample;
import com.drones.vision.domain.model.TrainingSampleId;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.DatasetExportPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for {@link LabelingController} (docs/CV-TRAINING-PLAN.md §3's frozen wire
 * contract), mirroring {@link DatasetControllerTest}/{@link MarksControllerTest}'s style: a
 * standalone {@code MockMvc} over mocked {@link LabelingService}/{@link DatasetService}/{@link
 * DatasetExportPort} collaborators, with {@link ApiExceptionHandler} attached so error mapping is
 * exercised exactly as it runs in production.
 */
class LabelingControllerTest {

    private LabelingService labelingService;
    private DatasetService datasetService;
    private DatasetExportPort datasetExportPort;
    private MockMvc mockMvc;

    private final UserId ownerId = UserId.random();
    private final Ownership ownership = new Ownership(ownerId, GroupId.random());
    private final CurrentUser currentUser = new CurrentUser(ownership);

    @BeforeEach
    void setUp() {
        labelingService = mock(LabelingService.class);
        datasetService = mock(DatasetService.class);
        datasetExportPort = mock(DatasetExportPort.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new LabelingController(labelingService, datasetService, datasetExportPort, currentUser))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private TrainingSample sample(TrainingSampleId id, DatasetId datasetId, StreamId streamId,
                                   List<Annotation> annotations, SampleStatus status) {
        return new TrainingSample(id, datasetId, streamId, AssetId.random(), Instant.parse("2026-08-01T10:00:01Z"),
                1920, 1080, annotations, status, null, null);
    }

    // ---- POST /api/streams/{streamId}/samples ----

    @Test
    void captureReturns201WithModelAnnotationsAndThreadsStreamAndDataset() throws Exception {
        StreamId streamId = StreamId.random();
        DatasetId datasetId = DatasetId.random();
        TrainingSampleId sampleId = TrainingSampleId.random();
        Annotation modelAnnotation =
                new Annotation("building", new BoundingBox(0.10, 0.20, 0.30, 0.25), AnnotationSource.MODEL);
        when(labelingService.capture(any(CaptureSpec.class), eq(ownerId), any(VisibilityScope.class)))
                .thenReturn(sample(sampleId, datasetId, streamId, List.of(modelAnnotation), SampleStatus.PENDING));

        mockMvc.perform(post("/api/streams/{streamId}/samples", streamId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasetId\": \"" + datasetId.value() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(sampleId.value().toString()))
                .andExpect(jsonPath("$.datasetId").value(datasetId.value().toString()))
                .andExpect(jsonPath("$.streamId").value(streamId.value().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.labeledBy").doesNotExist())
                .andExpect(jsonPath("$.annotations", hasSize(1)))
                .andExpect(jsonPath("$.annotations[0].label").value("building"))
                .andExpect(jsonPath("$.annotations[0].source").value("MODEL"))
                .andExpect(jsonPath("$.annotations[0].box.x").value(0.10));

        ArgumentCaptor<CaptureSpec> captor = ArgumentCaptor.forClass(CaptureSpec.class);
        verify(labelingService).capture(captor.capture(), eq(ownerId), eq(currentUser.scope()));
        assertEquals(streamId, captor.getValue().streamId());
        assertEquals(datasetId, captor.getValue().datasetId());
    }

    @Test
    void captureReturns404WhenStreamHasNoFrameYet() throws Exception {
        StreamId streamId = StreamId.random();
        when(labelingService.capture(any(CaptureSpec.class), eq(ownerId), any(VisibilityScope.class)))
                .thenThrow(new NoSuchElementException("No frame available for stream " + streamId.value()));

        mockMvc.perform(post("/api/streams/{streamId}/samples", streamId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasetId\": \"" + DatasetId.random().value() + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void captureReturns403WhenSourceAssetOutsideScope() throws Exception {
        StreamId streamId = StreamId.random();
        when(labelingService.capture(any(CaptureSpec.class), eq(ownerId), any(VisibilityScope.class)))
                .thenThrow(new AccessDeniedException("outside your scope"));

        mockMvc.perform(post("/api/streams/{streamId}/samples", streamId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasetId\": \"" + DatasetId.random().value() + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void captureReturns400ForABlankDatasetId() throws Exception {
        mockMvc.perform(post("/api/streams/{streamId}/samples", StreamId.random().value())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    // ---- GET /api/datasets/{id}/samples ----

    @Test
    void samplesReturns200WrappedUnderSamplesDefaultingLimitTo50() throws Exception {
        DatasetId datasetId = DatasetId.random();
        TrainingSampleId sampleId = TrainingSampleId.random();
        when(labelingService.samples(eq(datasetId), eq(null), eq(50), eq(ownerId), any(VisibilityScope.class)))
                .thenReturn(List.of(sample(sampleId, datasetId, StreamId.random(), List.of(), SampleStatus.PENDING)));

        mockMvc.perform(get("/api/datasets/{id}/samples", datasetId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.samples", hasSize(1)))
                .andExpect(jsonPath("$.samples[0].id").value(sampleId.value().toString()));
    }

    @Test
    void samplesHonorsStatusAndLimitQueryParameters() throws Exception {
        DatasetId datasetId = DatasetId.random();
        when(labelingService.samples(eq(datasetId), eq(SampleStatus.LABELED), eq(10), eq(ownerId), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/datasets/{id}/samples", datasetId.value())
                        .queryParam("status", "labeled").queryParam("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.samples", hasSize(0)));

        verify(labelingService).samples(eq(datasetId), eq(SampleStatus.LABELED), eq(10), eq(ownerId), any());
    }

    @Test
    void samplesReturns400ForAnUnrecognizedStatus() throws Exception {
        mockMvc.perform(get("/api/datasets/{id}/samples", DatasetId.random().value())
                        .queryParam("status", "sideways"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void samplesReturns404ForUnknownDataset() throws Exception {
        DatasetId datasetId = DatasetId.random();
        when(labelingService.samples(eq(datasetId), eq(null), anyInt(), eq(ownerId), any()))
                .thenThrow(new NoSuchElementException("Unknown dataset: " + datasetId.value()));

        mockMvc.perform(get("/api/datasets/{id}/samples", datasetId.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    // ---- GET /api/samples/{id}/image ----

    @Test
    void imageReturns200WithStoredBytesAndContentTypeNeverCached() throws Exception {
        TrainingSampleId id = TrainingSampleId.random();
        byte[] jpegBytes = {1, 2, 3, 4};
        when(labelingService.image(eq(id), eq(ownerId), any(VisibilityScope.class)))
                .thenReturn(new SampleImage(jpegBytes, "image/jpeg"));

        mockMvc.perform(get("/api/samples/{id}/image", id.value()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(content().bytes(jpegBytes))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void imageReturns404WhenNoImageStored() throws Exception {
        TrainingSampleId id = TrainingSampleId.random();
        when(labelingService.image(eq(id), eq(ownerId), any(VisibilityScope.class)))
                .thenThrow(new NoSuchElementException("No image stored for sample: " + id.value()));

        mockMvc.perform(get("/api/samples/{id}/image", id.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    // ---- PUT /api/samples/{id}/annotations ----

    @Test
    void labelReturns200AndThreadsCorrectedAnnotations() throws Exception {
        TrainingSampleId id = TrainingSampleId.random();
        DatasetId datasetId = DatasetId.random();
        Annotation corrected =
                new Annotation("building", new BoundingBox(0.11, 0.19, 0.32, 0.27), AnnotationSource.OPERATOR);
        when(labelingService.label(eq(id), any(LabelSpec.class), eq(ownerId), any(VisibilityScope.class)))
                .thenReturn(sample(id, datasetId, StreamId.random(), List.of(corrected), SampleStatus.LABELED));

        String body = """
                {"status": "LABELED",
                 "annotations": [{"label": "building", "source": "OPERATOR",
                                   "box": {"x": 0.11, "y": 0.19, "width": 0.32, "height": 0.27}}]}
                """;

        mockMvc.perform(put("/api/samples/{id}/annotations", id.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LABELED"))
                .andExpect(jsonPath("$.annotations[0].source").value("OPERATOR"));

        ArgumentCaptor<LabelSpec> captor = ArgumentCaptor.forClass(LabelSpec.class);
        verify(labelingService).label(eq(id), captor.capture(), eq(ownerId), any());
        assertEquals(SampleStatus.LABELED, captor.getValue().status());
        assertEquals(1, captor.getValue().annotations().size());
    }

    @Test
    void labelReturns400ForAnAnnotationLabelOutsideDatasetVocabulary() throws Exception {
        TrainingSampleId id = TrainingSampleId.random();
        when(labelingService.label(eq(id), any(LabelSpec.class), eq(ownerId), any()))
                .thenThrow(new IllegalArgumentException("Annotation label 'tank' is not a member of dataset classes"));

        String body = """
                {"status": "LABELED",
                 "annotations": [{"label": "tank", "source": "OPERATOR",
                                   "box": {"x": 0.1, "y": 0.1, "width": 0.1, "height": 0.1}}]}
                """;

        mockMvc.perform(put("/api/samples/{id}/annotations", id.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void labelReturns400ForAnUnrecognizedStatus() throws Exception {
        mockMvc.perform(put("/api/samples/{id}/annotations", TrainingSampleId.random().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"PENDING\", \"annotations\": []}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void labelReturns404ForUnknownSample() throws Exception {
        TrainingSampleId id = TrainingSampleId.random();
        when(labelingService.label(eq(id), any(LabelSpec.class), eq(ownerId), any()))
                .thenThrow(new NoSuchElementException("Unknown training sample: " + id.value()));

        mockMvc.perform(put("/api/samples/{id}/annotations", id.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"DISCARDED\", \"annotations\": []}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    // ---- POST /api/datasets/{id}/export ----

    @Test
    void exportReturns202WithManifestAndDownloadUrl() throws Exception {
        DatasetId datasetId = DatasetId.random();
        DatasetExport export = new DatasetExport(datasetId, "export-1", Instant.parse("2026-08-01T10:05:00Z"),
                List.of("building", "tower"), 40, 18234123L, "/tmp/export-1.zip");
        when(labelingService.export(eq(datasetId), eq(ownerId), any(VisibilityScope.class))).thenReturn(export);

        mockMvc.perform(post("/api/datasets/{id}/export", datasetId.value()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.datasetId").value(datasetId.value().toString()))
                .andExpect(jsonPath("$.exportId").value("export-1"))
                .andExpect(jsonPath("$.sampleCount").value(40))
                .andExpect(jsonPath("$.sizeBytes").value(18234123))
                .andExpect(jsonPath("$.downloadUrl")
                        .value("/api/datasets/" + datasetId.value() + "/export/export-1"));
    }

    @Test
    void exportReturns404ForUnknownDataset() throws Exception {
        DatasetId datasetId = DatasetId.random();
        when(labelingService.export(eq(datasetId), eq(ownerId), any()))
                .thenThrow(new NoSuchElementException("Unknown dataset: " + datasetId.value()));

        mockMvc.perform(post("/api/datasets/{id}/export", datasetId.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    // ---- GET /api/datasets/{id}/export/{exportId} ----

    @Test
    void downloadExportReturns200WithZipBytesAfterValidatingScope(@TempDir Path tempDir) throws Exception {
        DatasetId datasetId = DatasetId.random();
        Dataset dataset = new Dataset(datasetId, "Buildings", null, List.of(), ownership, DatasetStatus.OPEN,
                Instant.now());
        Path zipPath = tempDir.resolve("export.zip");
        Files.write(zipPath, new byte[] {80, 75, 3, 4});
        when(datasetService.get(eq(datasetId), eq(ownerId), any(VisibilityScope.class))).thenReturn(dataset);
        when(datasetExportPort.resolve(datasetId, "export-1")).thenReturn(Optional.of(zipPath));

        mockMvc.perform(get("/api/datasets/{id}/export/{exportId}", datasetId.value(), "export-1"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(Files.readAllBytes(zipPath)));

        verify(datasetService).get(datasetId, ownerId, currentUser.scope());
    }

    @Test
    void downloadExportReturns404ForAnUnknownExportId() throws Exception {
        DatasetId datasetId = DatasetId.random();
        Dataset dataset = new Dataset(datasetId, "Buildings", null, List.of(), ownership, DatasetStatus.OPEN,
                Instant.now());
        when(datasetService.get(eq(datasetId), eq(ownerId), any(VisibilityScope.class))).thenReturn(dataset);
        when(datasetExportPort.resolve(eq(datasetId), any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/datasets/{id}/export/{exportId}", datasetId.value(), "unknown-export"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void downloadExportReturns403ForADatasetOutsideCallersScope() throws Exception {
        DatasetId datasetId = DatasetId.random();
        when(datasetService.get(eq(datasetId), eq(ownerId), any(VisibilityScope.class)))
                .thenThrow(new AccessDeniedException("Dataset " + datasetId.value() + " is outside your scope"));

        mockMvc.perform(get("/api/datasets/{id}/export/{exportId}", datasetId.value(), "export-1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }
}

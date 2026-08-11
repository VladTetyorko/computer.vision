package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.application.pipeline.TrackingStats;
import com.drones.vision.application.stream.ActiveStream;
import com.drones.vision.application.stream.PipelineConfigPatch;
import com.drones.vision.application.stream.StreamService;
import com.drones.vision.application.exception.UnsupportedProtocolException;
import com.drones.vision.application.stream.UpdateOutcome;
import com.drones.vision.domain.model.BoundingBox;
import com.drones.vision.domain.model.Detection;
import com.drones.vision.domain.model.DetectionQuery;
import com.drones.vision.domain.model.DetectionResult;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.ModelRef;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.PixelFormat;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.TargetLock;
import com.drones.vision.domain.model.TrackRef;
import com.drones.vision.domain.model.TrackState;
import com.drones.vision.domain.model.TrackedObject;
import com.drones.vision.domain.model.TrackingConfig;
import com.drones.vision.domain.model.TrackingMode;
import com.drones.vision.domain.model.TrackingTelemetry;
import com.drones.vision.domain.model.DetectionSource;
import com.drones.vision.domain.model.DetectorReason;
import com.drones.vision.domain.model.VideoFrame;
import com.drones.vision.domain.port.out.DetectionRepositoryPort;
import com.drones.vision.domain.port.out.StreamPublisherPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import com.drones.vision.api.support.SnapshotJpegEncoder;
import com.drones.vision.api.support.VisionApiProperties;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StreamControllerTest {

    private StreamService streamService;
    private StreamPublisherPort streamPublisherPort;
    private DetectionRepositoryPort detectionRepositoryPort;
    private MockMvc mockMvc;

    private final DeviceId deviceId = DeviceId.random();

    @BeforeEach
    void setUp() {
        streamService = mock(StreamService.class);
        streamPublisherPort = mock(StreamPublisherPort.class);
        detectionRepositoryPort = mock(DetectionRepositoryPort.class);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new StreamController(streamService, streamPublisherPort, detectionRepositoryPort,
                        new SnapshotJpegEncoder(VisionApiProperties.defaults()), TrackingConfig.off()))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static DetectionResult detectionResult(StreamId streamId, long frameSequence, Instant capturedAt) {
        Detection detection = new Detection("person", 0.87, new BoundingBox(0.1, 0.2, 0.3, 0.4),
                new ModelRef("yolo", "latest"));
        return new DetectionResult(streamId, frameSequence, capturedAt, List.of(detection), Duration.ofMillis(42));
    }

    @Test
    void startReturns201WithStreamIdAndViewUrlWhenPublisherHasOne() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.start(any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId))
                .thenReturn(Optional.of(URI.create("http://localhost:8888/" + streamId.value() + "/index.m3u8")));

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.streamId").value(streamId.value().toString()))
                .andExpect(jsonPath("$.viewUrl")
                        .value("http://localhost:8888/" + streamId.value() + "/index.m3u8"));
    }

    @Test
    void startOmitsViewUrlWhenPublisherHasNone() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.start(any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.streamId").value(streamId.value().toString()))
                .andExpect(jsonPath("$.viewUrl").doesNotExist());
    }

    // ---- docs/MVP2-PLAN.md §L: whepUrl beside viewUrl ----

    @Test
    void startReturns201WithWhepUrlWhenPublisherHasOne() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.start(any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId))
                .thenReturn(Optional.of(URI.create("http://localhost:8888/" + streamId.value() + "/index.m3u8")));
        when(streamPublisherPort.whepUrl(streamId))
                .thenReturn(Optional.of(URI.create("http://localhost:18889/" + streamId.value() + "/whep")));

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.streamId").value(streamId.value().toString()))
                .andExpect(jsonPath("$.viewUrl")
                        .value("http://localhost:8888/" + streamId.value() + "/index.m3u8"))
                .andExpect(jsonPath("$.whepUrl")
                        .value("http://localhost:18889/" + streamId.value() + "/whep"));
    }

    @Test
    void startOmitsWhepUrlWhenPublisherHasNoWebRtcEndpoint() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.start(any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId))
                .thenReturn(Optional.of(URI.create("http://localhost:8888/" + streamId.value() + "/index.m3u8")));
        when(streamPublisherPort.whepUrl(streamId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.viewUrl")
                        .value("http://localhost:8888/" + streamId.value() + "/index.m3u8"))
                .andExpect(jsonPath("$.whepUrl").doesNotExist());
    }

    @Test
    void startUsesPipelineConfigDefaultsWhenBodyAbsent() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.start(any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value()))
                .andExpect(status().isCreated());

        ArgumentCaptor<PipelineConfig> captor = ArgumentCaptor.forClass(PipelineConfig.class);
        verify(streamService).start(eq(deviceId), captor.capture());
        assertEquals(PipelineConfig.defaults(), captor.getValue());
    }

    @Test
    void startMergesRequestOverridesOntoDefaults() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.start(any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId)).thenReturn(Optional.empty());

        String body = """
                {"confidenceThreshold":0.75,"inferenceFps":10}
                """;

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<PipelineConfig> captor = ArgumentCaptor.forClass(PipelineConfig.class);
        verify(streamService).start(eq(deviceId), captor.capture());
        PipelineConfig defaults = PipelineConfig.defaults();
        PipelineConfig config = captor.getValue();
        assertEquals(0.75, config.confidenceThreshold());
        assertEquals(10, config.inferenceFps());
        assertEquals(defaults.model(), config.model());
        assertEquals(defaults.maxInFlightInferences(), config.maxInFlightInferences());
        assertEquals(defaults.overlayTelemetry(), config.overlayTelemetry());
        assertEquals(defaults.labelFilter(), config.labelFilter());
    }

    @Test
    void startMergesPartialOverrideKeepingOtherDefault() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.start(any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId)).thenReturn(Optional.empty());

        String body = """
                {"inferenceFps":15}
                """;

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<PipelineConfig> captor = ArgumentCaptor.forClass(PipelineConfig.class);
        verify(streamService).start(eq(deviceId), captor.capture());
        PipelineConfig defaults = PipelineConfig.defaults();
        PipelineConfig config = captor.getValue();
        assertEquals(defaults.confidenceThreshold(), config.confidenceThreshold());
        assertEquals(15, config.inferenceFps());
    }

    @Test
    void startMergesOverlayBurnInOverrideOntoDefaults() throws Exception {
        // docs/MVP2-PLAN.md §V, V-e: overlayBurnIn is per-stream settable exactly like
        // confidenceThreshold/inferenceFps above.
        StreamId streamId = StreamId.random();
        when(streamService.start(any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId)).thenReturn(Optional.empty());

        String body = """
                {"overlayBurnIn":false}
                """;

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<PipelineConfig> captor = ArgumentCaptor.forClass(PipelineConfig.class);
        verify(streamService).start(eq(deviceId), captor.capture());
        PipelineConfig defaults = PipelineConfig.defaults();
        PipelineConfig config = captor.getValue();
        assertFalse(config.overlayBurnIn());
        assertEquals(defaults.confidenceThreshold(), config.confidenceThreshold());
        assertEquals(defaults.inferenceFps(), config.inferenceFps());
    }

    @Test
    void startMergesModelOverrideOntoDefaults() throws Exception {
        // The frontend's detection-model picker sends a model id here -- possibly a
        // comma-composite ("yolo11n.pt,orion12l.pt") cv-service's own registry parses
        // server-side; this DTO/PipelineConfig must carry it through verbatim, unsplit.
        StreamId streamId = StreamId.random();
        when(streamService.start(any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId)).thenReturn(Optional.empty());

        String body = """
                {"model":"yolo11n.pt,orion12l.pt"}
                """;

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<PipelineConfig> captor = ArgumentCaptor.forClass(PipelineConfig.class);
        verify(streamService).start(eq(deviceId), captor.capture());
        PipelineConfig defaults = PipelineConfig.defaults();
        PipelineConfig config = captor.getValue();
        assertEquals("yolo11n.pt,orion12l.pt", config.model().id());
        assertEquals(defaults.model().version(), config.model().version());
        assertEquals(defaults.confidenceThreshold(), config.confidenceThreshold());
        assertEquals(defaults.inferenceFps(), config.inferenceFps());
    }

    @Test
    void startWithoutModelKeepsTheDefaultModel() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.start(any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId)).thenReturn(Optional.empty());

        String body = """
                {"confidenceThreshold":0.6}
                """;

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<PipelineConfig> captor = ArgumentCaptor.forClass(PipelineConfig.class);
        verify(streamService).start(eq(deviceId), captor.capture());
        assertEquals(PipelineConfig.defaults().model(), captor.getValue().model());
    }

    @Test
    void startTreatsABlankModelAsAbsent() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.start(any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId)).thenReturn(Optional.empty());

        String body = """
                {"model":"   "}
                """;

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<PipelineConfig> captor = ArgumentCaptor.forClass(PipelineConfig.class);
        verify(streamService).start(eq(deviceId), captor.capture());
        assertEquals(PipelineConfig.defaults().model(), captor.getValue().model());
    }

    // ---- docs/CV-CONTROL-PLAN.md §2: labelFilter/detectionEnabled start overrides ----

    @Test
    void startMergesLabelFilterOverrideOntoDefaults() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.start(any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId)).thenReturn(Optional.empty());

        String body = """
                {"labelFilter":["person","car"]}
                """;

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<PipelineConfig> captor = ArgumentCaptor.forClass(PipelineConfig.class);
        verify(streamService).start(eq(deviceId), captor.capture());
        assertEquals(Set.of("person", "car"), captor.getValue().labelFilter());
    }

    @Test
    void startWithoutLabelFilterKeepsTheDefaultEmptySet() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.start(any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value()))
                .andExpect(status().isCreated());

        ArgumentCaptor<PipelineConfig> captor = ArgumentCaptor.forClass(PipelineConfig.class);
        verify(streamService).start(eq(deviceId), captor.capture());
        assertEquals(PipelineConfig.defaults().labelFilter(), captor.getValue().labelFilter());
    }

    @Test
    void startMergesDetectionEnabledFalseOverrideOntoDefaults() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.start(any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId)).thenReturn(Optional.empty());

        String body = """
                {"detectionEnabled":false}
                """;

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<PipelineConfig> captor = ArgumentCaptor.forClass(PipelineConfig.class);
        verify(streamService).start(eq(deviceId), captor.capture());
        assertFalse(captor.getValue().detectionEnabled());
    }

    @Test
    void startWithoutDetectionEnabledKeepsTheDefaultTrue() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.start(any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value()))
                .andExpect(status().isCreated());

        ArgumentCaptor<PipelineConfig> captor = ArgumentCaptor.forClass(PipelineConfig.class);
        verify(streamService).start(eq(deviceId), captor.capture());
        assertTrue(captor.getValue().detectionEnabled());
    }

    @Test
    void startReturns404WhenDeviceIsUnknown() throws Exception {
        when(streamService.start(any(), any()))
                .thenThrow(new NoSuchElementException("Unknown device: " + deviceId.value()));

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void startReturns409WhenDeviceAlreadyStreaming() throws Exception {
        when(streamService.start(any(), any()))
                .thenThrow(new IllegalStateException("Device already has an active stream: " + deviceId.value()));

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void startReturns400ForUnsupportedProtocol() throws Exception {
        when(streamService.start(any(), any())).thenThrow(new UnsupportedProtocolException("mjpeg"));

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void listReturnsActiveStreamsWithViewUrlWhenPresent() throws Exception {
        StreamId streamId = StreamId.random();
        Instant startedAt = Instant.parse("2026-07-22T10:00:00Z");
        when(streamService.streams())
                .thenReturn(List.of(new ActiveStream(streamId, deviceId, startedAt)));
        when(streamPublisherPort.viewUrl(streamId))
                .thenReturn(Optional.of(URI.create("http://localhost:8888/" + streamId.value() + "/index.m3u8")));

        mockMvc.perform(get("/api/streams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].streamId").value(streamId.value().toString()))
                .andExpect(jsonPath("$[0].deviceId").value(deviceId.value().toString()))
                .andExpect(jsonPath("$[0].startedAt").value("2026-07-22T10:00:00Z"))
                .andExpect(jsonPath("$[0].viewUrl")
                        .value("http://localhost:8888/" + streamId.value() + "/index.m3u8"));
    }

    @Test
    void listOmitsViewUrlWhenAbsent() throws Exception {
        StreamId streamId = StreamId.random();
        Instant startedAt = Instant.parse("2026-07-22T10:00:00Z");
        when(streamService.streams())
                .thenReturn(List.of(new ActiveStream(streamId, deviceId, startedAt)));
        when(streamPublisherPort.viewUrl(streamId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/streams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].viewUrl").doesNotExist());
    }

    @Test
    void listReturnsActiveStreamsWithWhepUrlWhenPresent() throws Exception {
        StreamId streamId = StreamId.random();
        Instant startedAt = Instant.parse("2026-07-22T10:00:00Z");
        when(streamService.streams())
                .thenReturn(List.of(new ActiveStream(streamId, deviceId, startedAt)));
        when(streamPublisherPort.viewUrl(streamId))
                .thenReturn(Optional.of(URI.create("http://localhost:8888/" + streamId.value() + "/index.m3u8")));
        when(streamPublisherPort.whepUrl(streamId))
                .thenReturn(Optional.of(URI.create("http://localhost:18889/" + streamId.value() + "/whep")));

        mockMvc.perform(get("/api/streams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].whepUrl")
                        .value("http://localhost:18889/" + streamId.value() + "/whep"));
    }

    @Test
    void listOmitsWhepUrlWhenAbsent() throws Exception {
        StreamId streamId = StreamId.random();
        Instant startedAt = Instant.parse("2026-07-22T10:00:00Z");
        when(streamService.streams())
                .thenReturn(List.of(new ActiveStream(streamId, deviceId, startedAt)));
        when(streamPublisherPort.viewUrl(streamId))
                .thenReturn(Optional.of(URI.create("http://localhost:8888/" + streamId.value() + "/index.m3u8")));
        when(streamPublisherPort.whepUrl(streamId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/streams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].whepUrl").doesNotExist());
    }

    @Test
    void listReturnsEmptyWhenNoActiveStreams() throws Exception {
        when(streamService.streams()).thenReturn(List.of());

        mockMvc.perform(get("/api/streams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void stopReturns204AndDelegatesToService() throws Exception {
        StreamId streamId = StreamId.random();

        mockMvc.perform(delete("/api/streams/{streamId}", streamId.value()))
                .andExpect(status().isNoContent());

        verify(streamService).stop(eq(streamId));
    }

    @Test
    void stopReturns204EvenWhenStreamIsUnknownPerNoOpContract() throws Exception {
        // StreamService.stop is documented as a no-op for unknown/already
        // stopped streams; the controller never sees an exception here, so
        // there is no 404 case for this endpoint.
        StreamId streamId = StreamId.random();

        mockMvc.perform(delete("/api/streams/{streamId}", streamId.value()))
                .andExpect(status().isNoContent());
    }

    @Test
    void detectionsMapsRepositoryResultsAndUsesDefaultLimitOfFifty() throws Exception {
        StreamId streamId = StreamId.random();
        Instant capturedAt = Instant.parse("2026-07-23T10:00:00Z");
        DetectionResult result = detectionResult(streamId, 7, capturedAt);
        when(detectionRepositoryPort.query(any())).thenReturn(List.of(result));

        mockMvc.perform(get("/api/streams/{streamId}/detections", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].streamId").value(streamId.value().toString()))
                .andExpect(jsonPath("$[0].frameSequence").value(7))
                .andExpect(jsonPath("$[0].capturedAt").value("2026-07-23T10:00:00Z"))
                .andExpect(jsonPath("$[0].inferenceMillis").value(42))
                .andExpect(jsonPath("$[0].detections", hasSize(1)))
                .andExpect(jsonPath("$[0].detections[0].label").value("person"))
                .andExpect(jsonPath("$[0].detections[0].confidence").value(0.87))
                .andExpect(jsonPath("$[0].detections[0].box.x").value(0.1))
                .andExpect(jsonPath("$[0].detections[0].box.y").value(0.2))
                .andExpect(jsonPath("$[0].detections[0].box.width").value(0.3))
                .andExpect(jsonPath("$[0].detections[0].box.height").value(0.4))
                .andExpect(jsonPath("$[0].detections[0].modelId").value("yolo"))
                .andExpect(jsonPath("$[0].detections[0].modelVersion").value("latest"));

        ArgumentCaptor<DetectionQuery> captor = ArgumentCaptor.forClass(DetectionQuery.class);
        verify(detectionRepositoryPort).query(captor.capture());
        DetectionQuery query = captor.getValue();
        assertEquals(streamId, query.streamId());
        assertEquals(50, query.limit());
        assertEquals(null, query.from());
        assertEquals(null, query.to());
        assertEquals(null, query.label());
    }

    @Test
    void detectionsPassesExplicitLimitThrough() throws Exception {
        StreamId streamId = StreamId.random();
        when(detectionRepositoryPort.query(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/streams/{streamId}/detections", streamId.value()).param("limit", "5"))
                .andExpect(status().isOk());

        ArgumentCaptor<DetectionQuery> captor = ArgumentCaptor.forClass(DetectionQuery.class);
        verify(detectionRepositoryPort).query(captor.capture());
        assertEquals(5, captor.getValue().limit());
    }

    @Test
    void detectionsSortsResultsNewestFirstRegardlessOfRepositoryOrder() throws Exception {
        StreamId streamId = StreamId.random();
        Instant older = Instant.parse("2026-07-23T10:00:00Z");
        Instant newer = Instant.parse("2026-07-23T10:00:05Z");
        DetectionResult olderResult = detectionResult(streamId, 1, older);
        DetectionResult newerResult = detectionResult(streamId, 2, newer);
        // Deliberately returned oldest-first, to prove the controller sorts rather than trusting order.
        when(detectionRepositoryPort.query(any())).thenReturn(List.of(olderResult, newerResult));

        mockMvc.perform(get("/api/streams/{streamId}/detections", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].frameSequence").value(2))
                .andExpect(jsonPath("$[1].frameSequence").value(1));
    }

    @Test
    void detectionsReturns400ForNonPositiveLimit() throws Exception {
        StreamId streamId = StreamId.random();

        mockMvc.perform(get("/api/streams/{streamId}/detections", streamId.value()).param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void detectionsReturnsEmptyListForUnknownStream() throws Exception {
        StreamId streamId = StreamId.random();
        when(detectionRepositoryPort.query(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/streams/{streamId}/detections", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ---- docs/CV-CONTROL-PLAN.md §3: PATCH /api/streams/{streamId}/config ----

    @Test
    void updateConfigReturnsStreamIdAndModelReArmedFalseForHotKnobs() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.updateConfig(eq(streamId), any())).thenReturn(new UpdateOutcome(false));

        String body = """
                {"confidenceThreshold":0.5,"inferenceFps":5}
                """;

        mockMvc.perform(patch("/api/streams/{streamId}/config", streamId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.streamId").value(streamId.value().toString()))
                .andExpect(jsonPath("$.modelReArmed").value(false));

        ArgumentCaptor<PipelineConfigPatch> captor = ArgumentCaptor.forClass(PipelineConfigPatch.class);
        verify(streamService).updateConfig(eq(streamId), captor.capture());
        PipelineConfigPatch patch = captor.getValue();
        assertEquals(0.5, patch.confidenceThreshold());
        assertEquals(5, patch.inferenceFps());
        assertNull(patch.labelFilter());
        assertNull(patch.detectionEnabled());
        assertNull(patch.modelId());
    }

    @Test
    void updateConfigReturnsModelReArmedTrueWhenTheModelChanged() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.updateConfig(eq(streamId), any())).thenReturn(new UpdateOutcome(true));

        String body = """
                {"model":"yoloe-26s-seg-pf.pt"}
                """;

        mockMvc.perform(patch("/api/streams/{streamId}/config", streamId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.streamId").value(streamId.value().toString()))
                .andExpect(jsonPath("$.modelReArmed").value(true));

        ArgumentCaptor<PipelineConfigPatch> captor = ArgumentCaptor.forClass(PipelineConfigPatch.class);
        verify(streamService).updateConfig(eq(streamId), captor.capture());
        assertEquals("yoloe-26s-seg-pf.pt", captor.getValue().modelId());
    }

    @Test
    void updateConfigThreadsLabelFilterAndDetectionEnabledThroughToThePatch() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.updateConfig(eq(streamId), any())).thenReturn(new UpdateOutcome(false));

        String body = """
                {"labelFilter":["person","building"],"detectionEnabled":false}
                """;

        mockMvc.perform(patch("/api/streams/{streamId}/config", streamId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<PipelineConfigPatch> captor = ArgumentCaptor.forClass(PipelineConfigPatch.class);
        verify(streamService).updateConfig(eq(streamId), captor.capture());
        PipelineConfigPatch patch = captor.getValue();
        assertEquals(Set.of("person", "building"), patch.labelFilter());
        assertFalse(patch.detectionEnabled());
    }

    @Test
    void updateConfigReturns404ForAnUnknownOrNotRunningStream() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.updateConfig(eq(streamId), any()))
                .thenThrow(new NoSuchElementException("Unknown or not-running stream: " + streamId.value()));

        mockMvc.perform(patch("/api/streams/{streamId}/config", streamId.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void updateConfigReturns400ForAnInvalidValue() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.updateConfig(eq(streamId), any()))
                .thenThrow(new IllegalArgumentException(
                        "PipelineConfig confidenceThreshold must be within [0,1]: 2.0"));

        String body = """
                {"confidenceThreshold":2.0}
                """;

        mockMvc.perform(patch("/api/streams/{streamId}/config", streamId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void updateConfigAcceptsAnAbsentBodyAsANoOpPatch() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.updateConfig(eq(streamId), any())).thenReturn(new UpdateOutcome(false));

        mockMvc.perform(patch("/api/streams/{streamId}/config", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelReArmed").value(false));

        ArgumentCaptor<PipelineConfigPatch> captor = ArgumentCaptor.forClass(PipelineConfigPatch.class);
        verify(streamService).updateConfig(eq(streamId), captor.capture());
        assertEquals(PipelineConfigPatch.NOTHING, captor.getValue());
    }

    // ---- docs/MVP3-PLAN.md C-a: GET /api/streams/{streamId}/snapshot ----

    private static byte[] tinyJpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    @Test
    void snapshotReturnsJpegBytesWithNoStoreCacheControl() throws Exception {
        StreamId streamId = StreamId.random();
        byte[] jpegBytes = tinyJpeg(2, 2); // already small: SnapshotJpegEncoder passes it through untouched
        VideoFrame frame = new VideoFrame(streamId, 0, Instant.now(), 2, 2, PixelFormat.JPEG,
                ByteBuffer.wrap(jpegBytes));
        when(streamService.latestFrame(streamId)).thenReturn(Optional.of(frame));

        mockMvc.perform(get("/api/streams/{streamId}/snapshot", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", MediaType.IMAGE_JPEG_VALUE))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(result -> assertArrayEquals(jpegBytes, result.getResponse().getContentAsByteArray()));
    }

    @Test
    void snapshotReturns404WhenTheStreamHasNoFrameYet() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.latestFrame(streamId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/streams/{streamId}/snapshot", streamId.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void snapshotReturns404ForAnUnknownStream() throws Exception {
        // StreamService#latestFrame makes no distinction between "unknown" and "known but no frame
        // yet" -- both are Optional.empty(), and both map to the same 404 here.
        when(streamService.latestFrame(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/streams/{streamId}/snapshot", StreamId.random().value()))
                .andExpect(status().isNotFound());
    }

    @Test
    void snapshotReturns400ForAMalformedStreamId() throws Exception {
        mockMvc.perform(get("/api/streams/{streamId}/snapshot", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    // ---- docs/TRACKING-PLAN.md §4.D: the `tracking` object on PATCH .../config ----

    @Test
    void updateConfigThreadsTheTrackingObjectThroughToThePatchAndReportsItChanged() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.updateConfig(eq(streamId), any())).thenReturn(new UpdateOutcome(false, true));

        String body = """
                {"tracking":{"mode":"FOLLOW","engineId":"lk","verifyEveryMillis":1500,"followFps":20,
                "redetectIouPercent":40,"maxAgeFrames":25,"minHits":2}}
                """;

        mockMvc.perform(patch("/api/streams/{streamId}/config", streamId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelReArmed").value(false))
                .andExpect(jsonPath("$.trackingChanged").value(true));

        ArgumentCaptor<PipelineConfigPatch> captor = ArgumentCaptor.forClass(PipelineConfigPatch.class);
        verify(streamService).updateConfig(eq(streamId), captor.capture());
        TrackingConfig tracking = captor.getValue().tracking();
        assertEquals(TrackingMode.FOLLOW, tracking.mode());
        assertEquals("lk", tracking.engineId());
        assertEquals(1500, tracking.verifyEveryMillis());
        assertEquals(20, tracking.followFps());
        assertEquals(40, tracking.redetectIouPercent());
        assertEquals(25, tracking.maxAgeFrames());
        assertEquals(2, tracking.minHits());
        assertNull(tracking.lock());
        // A tracking change is a hot knob: it must never look like a model swap.
        assertNull(captor.getValue().modelId());
    }

    @Test
    void updateConfigWithoutATrackingObjectLeavesTheTrackingPatchNullAndReadsNoStats() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.updateConfig(eq(streamId), any())).thenReturn(new UpdateOutcome(false));

        mockMvc.perform(patch("/api/streams/{streamId}/config", streamId.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"inferenceFps\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackingChanged").value(false));

        ArgumentCaptor<PipelineConfigPatch> captor = ArgumentCaptor.forClass(PipelineConfigPatch.class);
        verify(streamService).updateConfig(eq(streamId), captor.capture());
        assertNull(captor.getValue().tracking());
        verify(streamService, never()).trackingStats(any());
    }

    @Test
    void updateConfigLockCarriesLockSeqZeroForTheApplicationLayerToStamp() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.updateConfig(eq(streamId), any())).thenReturn(new UpdateOutcome(false, true));

        String body = """
                {"tracking":{"mode":"FOLLOW","lock":{"trackId":7}}}
                """;

        mockMvc.perform(patch("/api/streams/{streamId}/config", streamId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<PipelineConfigPatch> captor = ArgumentCaptor.forClass(PipelineConfigPatch.class);
        verify(streamService).updateConfig(eq(streamId), captor.capture());
        TargetLock lock = captor.getValue().tracking().lock();
        assertEquals(7L, lock.trackId());
        assertEquals(0L, lock.lockSeq(), "a client never allocates lockSeq -- DefaultStreamService stamps it");
        assertFalse(lock.release());
    }

    @Test
    void updateConfigReturns400WhenTheLockNamesTwoOfItsThreeForms() throws Exception {
        StreamId streamId = StreamId.random();

        String body = """
                {"tracking":{"mode":"FOLLOW","lock":{"trackId":7,"pointX":0.5,"pointY":0.5}}}
                """;

        mockMvc.perform(patch("/api/streams/{streamId}/config", streamId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verify(streamService, never()).updateConfig(any(), any());
    }

    @Test
    void updateConfigReturns400ForAnUnknownTrackingMode() throws Exception {
        StreamId streamId = StreamId.random();

        mockMvc.perform(patch("/api/streams/{streamId}/config", streamId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tracking\":{\"mode\":\"CHASE\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verify(streamService, never()).updateConfig(any(), any());
    }

    @Test
    void updateConfigMergesAPartialTrackingObjectOntoTheRunningStreamsReadableState() throws Exception {
        // The SPA sends one knob at a time ({"tracking":{"engineId":"ncc"}}), while the application
        // layer replaces mode/engine/cadences wholesale -- so an absent field falls back to what this
        // edge can actually read back: the configured mode and the engine actually serving.
        StreamId streamId = StreamId.random();
        when(streamService.trackingStats(streamId)).thenReturn(Optional.of(
                new TrackingStats(TrackingMode.FOLLOW, "lk", Duration.ofSeconds(30), 12, 348, 0.034, 0.4, 0.9,
                        DetectorReason.CADENCE, 7L, Map.of())));
        when(streamService.updateConfig(eq(streamId), any())).thenReturn(new UpdateOutcome(false, true));

        mockMvc.perform(patch("/api/streams/{streamId}/config", streamId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tracking\":{\"verifyEveryMillis\":5000}}"))
                .andExpect(status().isOk());

        ArgumentCaptor<PipelineConfigPatch> captor = ArgumentCaptor.forClass(PipelineConfigPatch.class);
        verify(streamService).updateConfig(eq(streamId), captor.capture());
        TrackingConfig tracking = captor.getValue().tracking();
        assertEquals(TrackingMode.FOLLOW, tracking.mode(), "a cadence tweak must not switch tracking off");
        assertEquals("lk", tracking.engineId(), "a cadence tweak must not reset the engine to the server default");
        assertEquals(5000, tracking.verifyEveryMillis());
        assertNull(tracking.lock(), "an absent lock leaves whatever the stream is holding alone");
    }

    @Test
    void updateConfigFallsBackToOffWhenTheStreamHasNoReadableTrackingState() throws Exception {
        StreamId streamId = StreamId.random();
        when(streamService.trackingStats(streamId)).thenReturn(Optional.empty());
        when(streamService.updateConfig(eq(streamId), any())).thenReturn(new UpdateOutcome(false, true));

        mockMvc.perform(patch("/api/streams/{streamId}/config", streamId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tracking\":{\"mode\":\"ASSOCIATE\"}}"))
                .andExpect(status().isOk());

        ArgumentCaptor<PipelineConfigPatch> captor = ArgumentCaptor.forClass(PipelineConfigPatch.class);
        verify(streamService).updateConfig(eq(streamId), captor.capture());
        assertEquals(TrackingMode.ASSOCIATE, captor.getValue().tracking().mode());
        assertEquals(TrackingConfig.DEFAULT_VERIFY_EVERY_MILLIS, captor.getValue().tracking().verifyEveryMillis());
    }

    // ---- docs/TRACKING-PLAN.md §4.D: the `tracking` object on POST /api/devices/{id}/stream ----

    @Test
    void startSeedsTrackingFromTheDeploymentDefaultsWhenTheRequestSaysNothing() throws Exception {
        // The controller under test here is wired with a non-default seed, exactly as vision-app's
        // TrackingWiring#streamStartTrackingDefaults would when vision.tracking.default-mode is set.
        TrackingConfig seed = new TrackingConfig(TrackingMode.ASSOCIATE, "", 2500, 20, 30, 30, 3, null);
        MockMvc seeded = MockMvcBuilders
                .standaloneSetup(new StreamController(streamService, streamPublisherPort, detectionRepositoryPort,
                        new SnapshotJpegEncoder(VisionApiProperties.defaults()), seed))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
        when(streamService.start(eq(deviceId), any())).thenReturn(StreamId.random());

        seeded.perform(post("/api/devices/{deviceId}/stream", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<PipelineConfig> captor = ArgumentCaptor.forClass(PipelineConfig.class);
        verify(streamService).start(eq(deviceId), captor.capture());
        assertEquals(seed, captor.getValue().tracking());
    }

    @Test
    void startMergesTheRequestsTrackingObjectOntoTheDeploymentSeed() throws Exception {
        when(streamService.start(eq(deviceId), any())).thenReturn(StreamId.random());

        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tracking\":{\"mode\":\"ASSOCIATE\",\"engineId\":\"bytetrack\"}}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<PipelineConfig> captor = ArgumentCaptor.forClass(PipelineConfig.class);
        verify(streamService).start(eq(deviceId), captor.capture());
        TrackingConfig tracking = captor.getValue().tracking();
        assertEquals(TrackingMode.ASSOCIATE, tracking.mode());
        assertEquals("bytetrack", tracking.engineId());
        assertEquals(TrackingConfig.DEFAULT_FOLLOW_FPS, tracking.followFps(), "absent fields keep the seed's values");
    }

    @Test
    void startReturns400WhenTheRequestTriesToLockATrackThatCannotExistYet() throws Exception {
        mockMvc.perform(post("/api/devices/{deviceId}/stream", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tracking\":{\"mode\":\"FOLLOW\",\"lock\":{\"trackId\":7}}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verify(streamService, never()).start(any(), any());
    }

    // ---- docs/TRACKING-PLAN.md §4.E: GET /api/streams/{streamId}/tracks ----

    @Test
    void tracksReturnsTheBookedTracksWithLockedTrackIdHoistedAboveStats() throws Exception {
        StreamId streamId = StreamId.random();
        Detection detection = new Detection("car", 0.82, new BoundingBox(0.31, 0.44, 0.09, 0.07),
                new ModelRef("yolo26n.pt", "latest"),
                new TrackRef(7L, TrackState.CONFIRMED, DetectionSource.TRACKER, 0.012, -0.001, 143));
        Instant firstSeen = Instant.parse("2026-08-11T10:22:31.104Z");
        Instant lastSeen = Instant.parse("2026-08-11T10:22:40.671Z");
        when(streamService.tracks(streamId))
                .thenReturn(List.of(new TrackedObject(7L, detection, firstSeen, lastSeen)));
        when(streamService.trackingStats(streamId)).thenReturn(Optional.of(
                new TrackingStats(TrackingMode.FOLLOW, "lk", Duration.ofSeconds(30), 12, 348, 0.034, 0.4, 0.9,
                        DetectorReason.CADENCE, 7L, Map.of(TrackState.CONFIRMED, 3, TrackState.COASTING, 1))));

        mockMvc.perform(get("/api/streams/{streamId}/tracks", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.streamId").value(streamId.value().toString()))
                .andExpect(jsonPath("$.lockedTrackId").value(7))
                .andExpect(jsonPath("$.stats.lockedTrackId").doesNotExist())
                .andExpect(jsonPath("$.tracks", hasSize(1)))
                .andExpect(jsonPath("$.tracks[0].trackId").value(7))
                .andExpect(jsonPath("$.tracks[0].label").value("car"))
                .andExpect(jsonPath("$.tracks[0].confidence").value(0.82))
                .andExpect(jsonPath("$.tracks[0].box.x").value(0.31))
                .andExpect(jsonPath("$.tracks[0].state").value("CONFIRMED"))
                .andExpect(jsonPath("$.tracks[0].source").value("TRACKER"))
                .andExpect(jsonPath("$.tracks[0].velocityX").value(0.012))
                .andExpect(jsonPath("$.tracks[0].ageFrames").value(143))
                .andExpect(jsonPath("$.tracks[0].firstSeen").value(firstSeen.toString()))
                .andExpect(jsonPath("$.tracks[0].lastSeen").value(lastSeen.toString()))
                .andExpect(jsonPath("$.stats.mode").value("FOLLOW"))
                .andExpect(jsonPath("$.stats.engineId").value("lk"))
                .andExpect(jsonPath("$.stats.windowSeconds").value(30))
                .andExpect(jsonPath("$.stats.detectorPasses").value(12))
                .andExpect(jsonPath("$.stats.trackerFrames").value(348))
                .andExpect(jsonPath("$.stats.dutyRatio").value(0.034))
                .andExpect(jsonPath("$.stats.trackerMillisP50").value(0.4))
                .andExpect(jsonPath("$.stats.trackerMillisP95").value(0.9))
                .andExpect(jsonPath("$.stats.lastDetectorReason").value("CADENCE"))
                .andExpect(jsonPath("$.stats.byState.CONFIRMED").value(3))
                .andExpect(jsonPath("$.stats.byState.LOST").value(0));
    }

    @Test
    void tracksReturnsAnEmptyListAndNoStatsForAnUnknownOrStoppedStream() throws Exception {
        when(streamService.tracks(any())).thenReturn(List.of());
        when(streamService.trackingStats(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/streams/{streamId}/tracks", StreamId.random().value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tracks", hasSize(0)))
                .andExpect(jsonPath("$.lockedTrackId").value(0))
                .andExpect(jsonPath("$.stats").doesNotExist());
    }

    @Test
    void tracksOmitsStatsUntilTheWindowHasSeenADetectorPass() throws Exception {
        // TrackingStats.empty(..) reports no lastDetectorReason at all; the flow strip has no
        // rendering for a half-populated strip, so the whole object is omitted instead.
        StreamId streamId = StreamId.random();
        when(streamService.tracks(streamId)).thenReturn(List.of());
        when(streamService.trackingStats(streamId))
                .thenReturn(Optional.of(TrackingStats.empty(TrackingMode.OFF, Duration.ofSeconds(30))));

        mockMvc.perform(get("/api/streams/{streamId}/tracks", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats").doesNotExist())
                .andExpect(jsonPath("$.lockedTrackId").value(0));
    }

    @Test
    void tracksReturns400ForAMalformedStreamId() throws Exception {
        mockMvc.perform(get("/api/streams/{streamId}/tracks", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    // ---- docs/TRACKING-PLAN.md §4.G: the nested track/tracking objects on the detections wire ----

    @Test
    void detectionsCarryTheNestedTrackAndTrackingObjectsWhenTrackingIsOn() throws Exception {
        StreamId streamId = StreamId.random();
        Detection tracked = new Detection("person", 0.9, new BoundingBox(0.1, 0.2, 0.3, 0.4),
                new ModelRef("yolo26n.pt", "latest"),
                new TrackRef(3L, TrackState.COASTING, DetectionSource.TRACKER, 0.01, -0.02, 12));
        DetectionResult result = new DetectionResult(streamId, 42, Instant.parse("2026-08-11T10:00:00Z"),
                List.of(tracked), Duration.ofMillis(7),
                new TrackingTelemetry(true, DetectorReason.CADENCE, Duration.ofNanos(400_000), "lk", 3L));
        when(detectionRepositoryPort.query(any(DetectionQuery.class))).thenReturn(List.of(result));

        mockMvc.perform(get("/api/streams/{streamId}/detections", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].detections[0].track.id").value(3))
                .andExpect(jsonPath("$[0].detections[0].track.state").value("COASTING"))
                .andExpect(jsonPath("$[0].detections[0].track.source").value("TRACKER"))
                .andExpect(jsonPath("$[0].detections[0].track.velocityX").value(0.01))
                .andExpect(jsonPath("$[0].detections[0].track.velocityY").value(-0.02))
                .andExpect(jsonPath("$[0].detections[0].track.ageFrames").doesNotExist())
                .andExpect(jsonPath("$[0].tracking.detectorRan").value(true))
                .andExpect(jsonPath("$[0].tracking.detectorReason").value("CADENCE"))
                .andExpect(jsonPath("$[0].tracking.trackerMillis").value(0.4))
                .andExpect(jsonPath("$[0].tracking.engineId").value("lk"))
                .andExpect(jsonPath("$[0].tracking.lockedTrackId").value(3));
    }

    @Test
    void detectionsOmitDetectorReasonOnATrackerOnlyFrame() throws Exception {
        StreamId streamId = StreamId.random();
        DetectionResult result = new DetectionResult(streamId, 43, Instant.parse("2026-08-11T10:00:01Z"),
                List.of(new Detection("person", 0.9, new BoundingBox(0.1, 0.2, 0.3, 0.4),
                        new ModelRef("yolo26n.pt", "latest"),
                        new TrackRef(3L, TrackState.CONFIRMED, DetectionSource.TRACKER))),
                Duration.ZERO,
                new TrackingTelemetry(false, null, Duration.ofNanos(370_000), "lk", 3L));
        when(detectionRepositoryPort.query(any(DetectionQuery.class))).thenReturn(List.of(result));

        mockMvc.perform(get("/api/streams/{streamId}/detections", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tracking.detectorRan").value(false))
                .andExpect(jsonPath("$[0].tracking.detectorReason").doesNotExist());
    }

    @Test
    void anUntrackedDetectionsPayloadIsByteIdenticalToThePreTrackingWire() throws Exception {
        StreamId streamId = StreamId.random();
        when(detectionRepositoryPort.query(any(DetectionQuery.class)))
                .thenReturn(List.of(detectionResult(streamId, 1, Instant.parse("2026-08-11T10:00:00Z"))));

        mockMvc.perform(get("/api/streams/{streamId}/detections", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].detections[0].track").doesNotExist())
                .andExpect(jsonPath("$[0].tracking").doesNotExist());
    }
}

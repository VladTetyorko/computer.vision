package com.drones.vision.api;

import com.drones.vision.application.ActiveStream;
import com.drones.vision.application.StreamService;
import com.drones.vision.application.UnsupportedProtocolException;
import com.drones.vision.domain.model.BoundingBox;
import com.drones.vision.domain.model.Detection;
import com.drones.vision.domain.model.DetectionQuery;
import com.drones.vision.domain.model.DetectionResult;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.ModelRef;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.port.out.DetectionRepositoryPort;
import com.drones.vision.domain.port.out.StreamPublisherPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                .standaloneSetup(new StreamController(streamService, streamPublisherPort, detectionRepositoryPort))
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
}

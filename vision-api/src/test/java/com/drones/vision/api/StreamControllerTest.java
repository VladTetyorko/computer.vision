package com.drones.vision.api;

import com.drones.vision.application.ActiveStream;
import com.drones.vision.application.StreamService;
import com.drones.vision.application.UnsupportedProtocolException;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.port.out.StreamPublisherPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

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

class StreamControllerTest {

    private StreamService streamService;
    private StreamPublisherPort streamPublisherPort;
    private MockMvc mockMvc;

    private final DeviceId deviceId = DeviceId.random();

    @BeforeEach
    void setUp() {
        streamService = mock(StreamService.class);
        streamPublisherPort = mock(StreamPublisherPort.class);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new StreamController(streamService, streamPublisherPort))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
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
}

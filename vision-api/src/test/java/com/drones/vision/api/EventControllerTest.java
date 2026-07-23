package com.drones.vision.api;

import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.DetectionEvent;
import com.drones.vision.domain.model.DetectionEventId;
import com.drones.vision.domain.model.DetectionEventState;
import com.drones.vision.domain.model.GeoPosition;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.port.out.DetectionEventRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EventControllerTest {

    private DetectionEventRepositoryPort detectionEventRepositoryPort;
    private MockMvc mockMvc;

    private final StreamId streamId = StreamId.random();
    private final AssetId assetId = AssetId.random();

    @BeforeEach
    void setUp() {
        detectionEventRepositoryPort = mock(DetectionEventRepositoryPort.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new EventController(detectionEventRepositoryPort))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private DetectionEvent event(String label, DetectionEventState state, AssetId assetId, GeoPosition position) {
        Instant t0 = Instant.parse("2024-01-01T00:00:00Z");
        return new DetectionEvent(DetectionEventId.random(), streamId, assetId, label, 0.87, t0,
                t0.plusSeconds(3), state, position);
    }

    @Test
    void recentReturnsMappedEventsNewestFirstOrderPreserved() throws Exception {
        DetectionEvent first = event("person", DetectionEventState.OPEN, assetId,
                new GeoPosition(10.0, 20.0, null));
        DetectionEvent second = event("car", DetectionEventState.CLOSED, null, null);
        when(detectionEventRepositoryPort.findRecent(isNull(), eq(EventController.DEFAULT_LIMIT)))
                .thenReturn(List.of(first, second));

        mockMvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(first.id().value().toString()))
                .andExpect(jsonPath("$[0].streamId").value(streamId.value().toString()))
                .andExpect(jsonPath("$[0].assetId").value(assetId.value().toString()))
                .andExpect(jsonPath("$[0].label").value("person"))
                .andExpect(jsonPath("$[0].peakConfidence").value(0.87))
                .andExpect(jsonPath("$[0].state").value("OPEN"))
                .andExpect(jsonPath("$[0].position.latitude").value(10.0))
                .andExpect(jsonPath("$[0].position.longitude").value(20.0))
                .andExpect(jsonPath("$[1].id").value(second.id().value().toString()))
                .andExpect(jsonPath("$[1].label").value("car"))
                .andExpect(jsonPath("$[1].state").value("CLOSED"))
                .andExpect(jsonPath("$[1].assetId").doesNotExist())
                .andExpect(jsonPath("$[1].position").doesNotExist());
    }

    @Test
    void recentPassesSinceMsThroughAsAnInstant() throws Exception {
        when(detectionEventRepositoryPort.findRecent(any(), anyInt())).thenReturn(List.of());
        long sinceMs = 1_700_000_000_000L;

        mockMvc.perform(get("/api/events").param("sinceMs", String.valueOf(sinceMs)))
                .andExpect(status().isOk());

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(detectionEventRepositoryPort).findRecent(captor.capture(), eq(EventController.DEFAULT_LIMIT));
        assertEquals(Instant.ofEpochMilli(sinceMs), captor.getValue());
    }

    @Test
    void recentPassesNullSinceWhenAbsent() throws Exception {
        when(detectionEventRepositoryPort.findRecent(any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/events")).andExpect(status().isOk());

        verify(detectionEventRepositoryPort).findRecent(isNull(), eq(EventController.DEFAULT_LIMIT));
    }

    @Test
    void recentPassesLimitThrough() throws Exception {
        when(detectionEventRepositoryPort.findRecent(any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/events").param("limit", "5")).andExpect(status().isOk());

        verify(detectionEventRepositoryPort).findRecent(isNull(), eq(5));
    }

    @Test
    void recentReturns400ForNonPositiveLimit() throws Exception {
        mockMvc.perform(get("/api/events").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        mockMvc.perform(get("/api/events").param("limit", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void forStreamReturnsMappedEvents() throws Exception {
        DetectionEvent detected = event("person", DetectionEventState.OPEN, assetId, null);
        when(detectionEventRepositoryPort.findByStream(eq(streamId), eq(EventController.DEFAULT_LIMIT)))
                .thenReturn(List.of(detected));

        mockMvc.perform(get("/api/streams/{streamId}/events", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].label").value("person"));
    }

    @Test
    void forStreamPassesLimitThrough() throws Exception {
        when(detectionEventRepositoryPort.findByStream(eq(streamId), eq(10))).thenReturn(List.of());

        mockMvc.perform(get("/api/streams/{streamId}/events", streamId.value()).param("limit", "10"))
                .andExpect(status().isOk());

        verify(detectionEventRepositoryPort).findByStream(streamId, 10);
    }

    @Test
    void forStreamReturns400ForNonPositiveLimit() throws Exception {
        mockMvc.perform(get("/api/streams/{streamId}/events", streamId.value()).param("limit", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void forStreamReturns400ForAMalformedStreamId() throws Exception {
        mockMvc.perform(get("/api/streams/{streamId}/events", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void forStreamReturnsAnEmptyListRatherThan404ForAnUnknownStream() throws Exception {
        StreamId unknown = StreamId.random();
        when(detectionEventRepositoryPort.findByStream(eq(unknown), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/streams/{streamId}/events", unknown.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }
}

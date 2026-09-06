package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.platform.Event;
import com.drones.vision.platform.EventHistoryPort;
import com.drones.vision.platform.EventType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

/**
 * MockMvc tests for {@link SystemEventsController} (docs/plans/active/ALWAYS-ON-FLOW-PLAN.md wave
 * B3). Deliberately no {@code CurrentUser}/scope collaborator in these tests — {@link
 * SystemEventsController#recent} is {@code @OpenByDesign}; {@link EndpointAuthorizationTest}
 * (vision-app) is the test that proves the annotation is actually present and load-bearing, not
 * this class.
 */
class SystemEventsControllerTest {

    private EventHistoryPort eventHistoryPort;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        eventHistoryPort = mock(EventHistoryPort.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new SystemEventsController(eventHistoryPort))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static Event event(EventType type, StreamId streamId, String message, Map<String, String> attributes) {
        return new Event(UUID.randomUUID().toString(), streamId, Instant.parse("2024-01-01T00:00:00Z"),
                type, message, attributes);
    }

    @Test
    void recentReturnsMappedEventsNewestFirstOrderPreserved() throws Exception {
        StreamId streamId = StreamId.random();
        Event first = event(EventType.STREAM_STARTED, streamId, "stream started", Map.of());
        Event second = event(EventType.BATTERY_LOW, null, "battery critical", Map.of("assetId", "abc"));
        when(eventHistoryPort.findSince(isNull(), eq(SystemEventsController.DEFAULT_LIMIT)))
                .thenReturn(List.of(first, second));

        mockMvc.perform(get("/api/system/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(first.id()))
                .andExpect(jsonPath("$[0].streamId").value(streamId.value().toString()))
                .andExpect(jsonPath("$[0].type").value("STREAM_STARTED"))
                .andExpect(jsonPath("$[0].message").value("stream started"))
                .andExpect(jsonPath("$[1].id").value(second.id()))
                .andExpect(jsonPath("$[1].streamId").doesNotExist())
                .andExpect(jsonPath("$[1].type").value("BATTERY_LOW"))
                .andExpect(jsonPath("$[1].attributes.assetId").value("abc"));
    }

    @Test
    void recentPassesSinceMsThroughAsAnInstant() throws Exception {
        when(eventHistoryPort.findSince(any(), anyInt())).thenReturn(List.of());
        long sinceMs = 1_700_000_000_000L;

        mockMvc.perform(get("/api/system/events").param("sinceMs", String.valueOf(sinceMs)))
                .andExpect(status().isOk());

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(eventHistoryPort).findSince(captor.capture(), eq(SystemEventsController.DEFAULT_LIMIT));
        assertEquals(Instant.ofEpochMilli(sinceMs), captor.getValue());
    }

    @Test
    void recentPassesNullSinceWhenAbsent() throws Exception {
        when(eventHistoryPort.findSince(any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/system/events")).andExpect(status().isOk());

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(eventHistoryPort).findSince(captor.capture(), eq(SystemEventsController.DEFAULT_LIMIT));
        assertNull(captor.getValue());
    }

    @Test
    void recentPassesLimitThrough() throws Exception {
        when(eventHistoryPort.findSince(any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/system/events").param("limit", "5")).andExpect(status().isOk());

        verify(eventHistoryPort).findSince(isNull(), eq(5));
    }

    @Test
    void recentReturns400ForNonPositiveLimit() throws Exception {
        mockMvc.perform(get("/api/system/events").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        mockMvc.perform(get("/api/system/events").param("limit", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recentReturnsAnEmptyListWhenHistoryIsEmpty() throws Exception {
        when(eventHistoryPort.findSince(any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/system/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }
}

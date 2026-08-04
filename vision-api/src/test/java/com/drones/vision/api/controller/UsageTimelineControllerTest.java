package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.application.replay.ReplayService;
import com.drones.vision.application.replay.UsageRecording;
import com.drones.vision.application.replay.UsageTimeline;
import com.drones.vision.application.scope.VisibilityScope;
import com.drones.vision.application.usage.UsageService;
import com.drones.vision.application.usage.UsageSummary;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.AssetUsage;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.Telemetry;
import com.drones.vision.domain.model.UsageId;
import com.drones.vision.domain.model.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

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

class UsageTimelineControllerTest {

    private static final DeviceId DEVICE_ID = DeviceId.random();

    private ReplayService replayService;
    private UsageService usageService;
    private CurrentUser currentUser;
    private MockMvc mockMvc;

    private final UsageId usageId = UsageId.random();
    private final AssetId assetId = AssetId.random();

    @BeforeEach
    void setUp() {
        replayService = mock(ReplayService.class);
        usageService = mock(UsageService.class);
        currentUser = new CurrentUser(new Ownership(UserId.random(), GroupId.random()));
        mockMvc = MockMvcBuilders
                .standaloneSetup(new UsageTimelineController(replayService, usageService, currentUser))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static Telemetry sampleAt(Instant at) {
        return new Telemetry(DEVICE_ID, at, 10.0, 20.0, 30.0, 90.0, 75.0, Map.of());
    }

    private AssetUsage usage(Instant startedAt, Instant endedAt) {
        return new AssetUsage(usageId, assetId, startedAt, endedAt, null, null, 2);
    }

    // ---- recent (docs/NAV-IA-REDESIGN-PLAN.md Wave 4, F8 -- the replay library list) ----

    @Test
    void recentReturns200WithMappedRowsOnHappyPath() throws Exception {
        Instant start = Instant.parse("2026-08-04T10:36:29.895Z");
        Instant end = Instant.parse("2026-08-04T11:20:00.000Z");
        UsageSummary summary = new UsageSummary(usageId, assetId, "Falcon-2", start, end, 2610L, 1234);
        when(usageService.recent(eq(VisibilityScope.unbounded()), isNull(), eq(50))).thenReturn(List.of(summary));

        mockMvc.perform(get("/api/usages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].usageId").value(usageId.value().toString()))
                .andExpect(jsonPath("$[0].assetId").value(assetId.value().toString()))
                .andExpect(jsonPath("$[0].assetName").value("Falcon-2"))
                .andExpect(jsonPath("$[0].startedAt").value("2026-08-04T10:36:29.895Z"))
                .andExpect(jsonPath("$[0].endedAt").value("2026-08-04T11:20:00Z"))
                .andExpect(jsonPath("$[0].durationSeconds").value(2610))
                .andExpect(jsonPath("$[0].sampleCount").value(1234));
    }

    @Test
    void recentOmitsEndedAtAndDurationSecondsForAStillOpenUsage() throws Exception {
        Instant start = Instant.parse("2026-08-04T10:36:29.895Z");
        UsageSummary summary = new UsageSummary(usageId, assetId, "Falcon-2", start, null, null, 12);
        when(usageService.recent(eq(VisibilityScope.unbounded()), isNull(), eq(50))).thenReturn(List.of(summary));

        mockMvc.perform(get("/api/usages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].endedAt").doesNotExist())
                .andExpect(jsonPath("$[0].durationSeconds").doesNotExist());
    }

    @Test
    void recentDefaultsLimitTo50WhenAbsent() throws Exception {
        when(usageService.recent(any(), any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/usages")).andExpect(status().isOk());

        verify(usageService).recent(eq(VisibilityScope.unbounded()), isNull(), eq(50));
    }

    @Test
    void recentPassesRequestedLimitThrough() throws Exception {
        when(usageService.recent(any(), any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/usages").param("limit", "5")).andExpect(status().isOk());

        verify(usageService).recent(eq(VisibilityScope.unbounded()), isNull(), eq(5));
    }

    @Test
    void recentPassesParsedAssetIdThrough() throws Exception {
        when(usageService.recent(any(), any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/usages").param("assetId", assetId.value().toString()))
                .andExpect(status().isOk());

        verify(usageService).recent(eq(VisibilityScope.unbounded()), eq(assetId), eq(50));
    }

    @Test
    void recentReturns400ForMalformedAssetId() throws Exception {
        mockMvc.perform(get("/api/usages").param("assetId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void recentReturns400WhenServiceRejectsNonPositiveLimit() throws Exception {
        when(usageService.recent(any(), any(), eq(0)))
                .thenThrow(new IllegalArgumentException("limit must be positive: 0"));

        mockMvc.perform(get("/api/usages").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void returns200WithMergedTimelineOnHappyPath() throws Exception {
        Instant start = Instant.parse("2026-07-22T10:00:00Z");
        Instant end = start.plusSeconds(60);
        Telemetry sample = sampleAt(start.plusSeconds(5));
        UsageTimeline timeline = new UsageTimeline(usage(start, end), start, end, List.of(sample), List.of());
        when(replayService.timeline(eq(usageId), any(), any(), anyInt())).thenReturn(timeline);

        mockMvc.perform(get("/api/usages/{usageId}/timeline", usageId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.usageId").value(usageId.value().toString()))
                .andExpect(jsonPath("$.from").value("2026-07-22T10:00:00Z"))
                .andExpect(jsonPath("$.to").value("2026-07-22T10:01:00Z"))
                .andExpect(jsonPath("$.telemetry", hasSize(1)))
                .andExpect(jsonPath("$.telemetry[0].deviceId").value(DEVICE_ID.value().toString()))
                .andExpect(jsonPath("$.telemetry[0].latitude").value(10.0))
                .andExpect(jsonPath("$.detections", hasSize(0)));
    }

    @Test
    void passesFromMsAndToMsAsInstantsForWindowing() throws Exception {
        Instant start = Instant.parse("2026-07-22T10:00:00Z");
        Instant end = start.plusSeconds(60);
        UsageTimeline timeline = new UsageTimeline(usage(start, end), start, end, List.of(), List.of());
        when(replayService.timeline(eq(usageId), any(), any(), anyInt())).thenReturn(timeline);

        long fromMs = start.toEpochMilli();
        long toMs = end.toEpochMilli();

        mockMvc.perform(get("/api/usages/{usageId}/timeline", usageId.value())
                        .param("fromMs", String.valueOf(fromMs))
                        .param("toMs", String.valueOf(toMs)))
                .andExpect(status().isOk());

        ArgumentCaptor<Instant> fromCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> toCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(replayService).timeline(eq(usageId), fromCaptor.capture(), toCaptor.capture(), eq(500));
        assertEquals(Instant.ofEpochMilli(fromMs), fromCaptor.getValue());
        assertEquals(Instant.ofEpochMilli(toMs), toCaptor.getValue());
    }

    @Test
    void defaultsFromMsAndToMsToNullWhenAbsentSoServiceAppliesUsageDefaults() throws Exception {
        Instant start = Instant.parse("2026-07-22T10:00:00Z");
        Instant end = start.plusSeconds(60);
        UsageTimeline timeline = new UsageTimeline(usage(start, end), start, end, List.of(), List.of());
        when(replayService.timeline(eq(usageId), any(), any(), anyInt())).thenReturn(timeline);

        mockMvc.perform(get("/api/usages/{usageId}/timeline", usageId.value()))
                .andExpect(status().isOk());

        ArgumentCaptor<Instant> fromCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> toCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(replayService).timeline(eq(usageId), fromCaptor.capture(), toCaptor.capture(), eq(500));
        assertNull(fromCaptor.getValue());
        assertNull(toCaptor.getValue());
    }

    @Test
    void passesRequestedMaxPointsThrough() throws Exception {
        Instant start = Instant.parse("2026-07-22T10:00:00Z");
        UsageTimeline timeline = new UsageTimeline(usage(start, start.plusSeconds(1)), start,
                start.plusSeconds(1), List.of(), List.of());
        when(replayService.timeline(eq(usageId), any(), any(), anyInt())).thenReturn(timeline);

        mockMvc.perform(get("/api/usages/{usageId}/timeline", usageId.value())
                        .param("maxPoints", "42"))
                .andExpect(status().isOk());

        verify(replayService).timeline(eq(usageId), any(), any(), eq(42));
    }

    @Test
    void resultOrderFromServiceIsPreservedInResponseForDownsamplingDeterminism() throws Exception {
        Instant start = Instant.parse("2026-07-22T10:00:00Z");
        List<Telemetry> thinned = List.of(
                sampleAt(start),
                sampleAt(start.plusSeconds(20)),
                sampleAt(start.plusSeconds(50)),
                sampleAt(start.plusSeconds(70)),
                sampleAt(start.plusSeconds(90)));
        UsageTimeline timeline = new UsageTimeline(usage(start, start.plusSeconds(90)), start,
                start.plusSeconds(90), thinned, List.of());
        when(replayService.timeline(eq(usageId), any(), any(), anyInt())).thenReturn(timeline);

        mockMvc.perform(get("/api/usages/{usageId}/timeline", usageId.value()).param("maxPoints", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.telemetry", hasSize(5)))
                .andExpect(jsonPath("$.telemetry[0].at").value("2026-07-22T10:00:00Z"))
                .andExpect(jsonPath("$.telemetry[1].at").value("2026-07-22T10:00:20Z"))
                .andExpect(jsonPath("$.telemetry[2].at").value("2026-07-22T10:00:50Z"))
                .andExpect(jsonPath("$.telemetry[3].at").value("2026-07-22T10:01:10Z"))
                .andExpect(jsonPath("$.telemetry[4].at").value("2026-07-22T10:01:30Z"));
    }

    @Test
    void returns404ForUnknownUsage() throws Exception {
        when(replayService.timeline(eq(usageId), any(), any(), anyInt()))
                .thenThrow(new NoSuchElementException("Unknown usage: " + usageId.value()));

        mockMvc.perform(get("/api/usages/{usageId}/timeline", usageId.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void returns400ForMalformedUsageId() throws Exception {
        mockMvc.perform(get("/api/usages/{usageId}/timeline", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void returns400WhenServiceRejectsNonPositiveMaxPoints() throws Exception {
        when(replayService.timeline(eq(usageId), any(), any(), eq(0)))
                .thenThrow(new IllegalArgumentException("maxPoints must be positive: 0"));

        mockMvc.perform(get("/api/usages/{usageId}/timeline", usageId.value()).param("maxPoints", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void returns400WhenServiceRejectsToBeforeFrom() throws Exception {
        when(replayService.timeline(eq(usageId), any(), any(), anyInt()))
                .thenThrow(new IllegalArgumentException("to must not be before from"));

        mockMvc.perform(get("/api/usages/{usageId}/timeline", usageId.value())
                        .param("fromMs", "2000")
                        .param("toMs", "1000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void openUsageDefaultWindowStillReturns200WithResolvedToFromService() throws Exception {
        Instant start = Instant.parse("2026-07-22T10:00:00Z");
        Instant resolvedNow = Instant.parse("2026-07-22T10:05:00Z");
        UsageTimeline timeline = new UsageTimeline(usage(start, null), start, resolvedNow, List.of(), List.of());
        when(replayService.timeline(eq(usageId), any(), any(), anyInt())).thenReturn(timeline);

        mockMvc.perform(get("/api/usages/{usageId}/timeline", usageId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.endedAt").doesNotExist())
                .andExpect(jsonPath("$.from").value("2026-07-22T10:00:00Z"))
                .andExpect(jsonPath("$.to").value("2026-07-22T10:05:00Z"));

        verify(replayService).timeline(eq(usageId), eq(null), eq(null), eq(500));
    }

    // ---- recording (docs/OPS-CORE-PLAN.md §R, R-b) ----

    @Test
    void recordingReturns200WithAvailableTrueAndTheResolvedRecording() throws Exception {
        Instant start = Instant.parse("2026-07-22T10:00:00Z");
        URI url = URI.create("http://localhost:19996/get?path=abc&start=2026-07-22T10%3A00%3A00Z&duration=60");
        when(replayService.recordingFor(usageId)).thenReturn(Optional.of(new UsageRecording(url, start, 60L)));

        mockMvc.perform(get("/api/usages/{usageId}/recording", usageId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.url").value(url.toString()))
                .andExpect(jsonPath("$.start").value("2026-07-22T10:00:00Z"))
                .andExpect(jsonPath("$.durationSeconds").value(60));
    }

    @Test
    void recordingReturns200WithAvailableFalseWhenNoneIsResolved() throws Exception {
        when(replayService.recordingFor(usageId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/usages/{usageId}/recording", usageId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.url").doesNotExist())
                .andExpect(jsonPath("$.start").doesNotExist())
                .andExpect(jsonPath("$.durationSeconds").doesNotExist());
    }

    @Test
    void recordingReturns404ForUnknownUsage() throws Exception {
        when(replayService.recordingFor(usageId))
                .thenThrow(new NoSuchElementException("Unknown usage: " + usageId.value()));

        mockMvc.perform(get("/api/usages/{usageId}/recording", usageId.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void recordingReturns400ForMalformedUsageId() throws Exception {
        mockMvc.perform(get("/api/usages/{usageId}/recording", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }
}

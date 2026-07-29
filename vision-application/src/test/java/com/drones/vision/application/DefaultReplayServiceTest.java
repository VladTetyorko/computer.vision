package com.drones.vision.application;

import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.AssetUsage;
import com.drones.vision.domain.model.DetectionQuery;
import com.drones.vision.domain.model.DetectionResult;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.Telemetry;
import com.drones.vision.domain.model.UsageId;
import com.drones.vision.domain.port.out.AssetUsageRepositoryPort;
import com.drones.vision.domain.port.out.DetectionRepositoryPort;
import com.drones.vision.domain.port.out.StreamPublisherPort;
import com.drones.vision.domain.port.out.TelemetryRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultReplayServiceTest {

    private static final DeviceId DEVICE_ID = DeviceId.random();
    private static final StreamId STREAM_ID = StreamId.random();

    private AssetUsageRepositoryPort usageRepository;
    private TelemetryRepositoryPort telemetryRepository;
    private DetectionRepositoryPort detectionRepository;
    private StreamPublisherPort streamPublisherPort;
    private DefaultReplayService service;
    private UsageId usageId;
    private AssetId assetId;

    @BeforeEach
    void setUp() {
        usageRepository = mock(AssetUsageRepositoryPort.class);
        telemetryRepository = mock(TelemetryRepositoryPort.class);
        detectionRepository = mock(DetectionRepositoryPort.class);
        streamPublisherPort = mock(StreamPublisherPort.class);
        service = new DefaultReplayService(usageRepository, telemetryRepository, detectionRepository,
                streamPublisherPort);
        usageId = UsageId.random();
        assetId = AssetId.random();
    }

    private static Telemetry sampleAt(Instant at) {
        return new Telemetry(DEVICE_ID, at, 1.0, 2.0, 3.0, null, null, Map.of());
    }

    private static DetectionResult detectionAt(Instant at) {
        return new DetectionResult(STREAM_ID, 0, at, List.of(), Duration.ZERO);
    }

    private AssetUsage closedUsage(Instant startedAt, Instant endedAt) {
        return new AssetUsage(usageId, assetId, startedAt, endedAt, null, null, 0);
    }

    private AssetUsage openUsage(Instant startedAt) {
        return new AssetUsage(usageId, assetId, startedAt, null, null, null, 0);
    }

    private AssetUsage closedUsageWithStream(Instant startedAt, Instant endedAt) {
        return new AssetUsage(usageId, assetId, startedAt, endedAt, null, null, 0, STREAM_ID);
    }

    private AssetUsage openUsageWithStream(Instant startedAt) {
        return new AssetUsage(usageId, assetId, startedAt, null, null, null, 0, STREAM_ID);
    }

    @Test
    void throwsNoSuchElementExceptionForUnknownUsage() {
        when(usageRepository.findById(usageId)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> service.timeline(usageId, null, null, 500));
    }

    @Test
    void rejectsNonPositiveMaxPoints() {
        Instant start = Instant.parse("2026-07-01T00:00:00Z");
        when(usageRepository.findById(usageId)).thenReturn(Optional.of(closedUsage(start, start.plusSeconds(60))));

        assertThrows(IllegalArgumentException.class, () -> service.timeline(usageId, null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> service.timeline(usageId, null, null, -1));
    }

    @Test
    void rejectsToBeforeFrom() {
        Instant start = Instant.parse("2026-07-01T00:00:00Z");
        when(usageRepository.findById(usageId)).thenReturn(Optional.of(closedUsage(start, start.plusSeconds(60))));

        assertThrows(IllegalArgumentException.class,
                () -> service.timeline(usageId, start.plusSeconds(30), start.plusSeconds(10), 500));
    }

    @Test
    void defaultsWindowToUsageStartAndEndWhenClosed() {
        Instant start = Instant.parse("2026-07-01T00:00:00Z");
        Instant end = start.plusSeconds(120);
        when(usageRepository.findById(usageId)).thenReturn(Optional.of(closedUsage(start, end)));
        when(telemetryRepository.findByUsage(eq(usageId), any(Integer.class))).thenReturn(List.of());

        UsageTimeline timeline = service.timeline(usageId, null, null, 500);

        assertEquals(start, timeline.from());
        assertEquals(end, timeline.to());
    }

    @Test
    void defaultsWindowUpperBoundToNowForOpenUsage() {
        Instant start = Instant.parse("2026-07-01T00:00:00Z");
        when(usageRepository.findById(usageId)).thenReturn(Optional.of(openUsage(start)));
        when(telemetryRepository.findByUsage(eq(usageId), any(Integer.class))).thenReturn(List.of());

        Instant before = Instant.now();
        UsageTimeline timeline = service.timeline(usageId, null, null, 500);
        Instant after = Instant.now();

        assertEquals(start, timeline.from());
        assertFalse(timeline.to().isBefore(before), "expected to() >= before");
        assertFalse(timeline.to().isAfter(after), "expected to() <= after");
    }

    @Test
    void queriesTelemetryRepositoryWithTheFetchLimitConstant() {
        Instant start = Instant.parse("2026-07-01T00:00:00Z");
        when(usageRepository.findById(usageId)).thenReturn(Optional.of(closedUsage(start, start.plusSeconds(60))));
        when(telemetryRepository.findByUsage(any(), any(Integer.class))).thenReturn(List.of());

        service.timeline(usageId, null, null, 500);

        verify(telemetryRepository).findByUsage(usageId, DefaultReplayService.TELEMETRY_FETCH_LIMIT);
    }

    @Test
    void filtersTelemetrySamplesOutsideTheRequestedWindow() {
        Instant start = Instant.parse("2026-07-01T00:00:00Z");
        Instant end = start.plusSeconds(100);
        when(usageRepository.findById(usageId)).thenReturn(Optional.of(closedUsage(start, end)));

        Telemetry before = sampleAt(start.minusSeconds(10));
        Telemetry lowerBound = sampleAt(start.plusSeconds(20));
        Telemetry inside = sampleAt(start.plusSeconds(30));
        Telemetry upperBound = sampleAt(start.plusSeconds(40));
        Telemetry after = sampleAt(start.plusSeconds(50));
        when(telemetryRepository.findByUsage(eq(usageId), any(Integer.class)))
                .thenReturn(List.of(before, lowerBound, inside, upperBound, after));

        UsageTimeline timeline = service.timeline(usageId, start.plusSeconds(20), start.plusSeconds(40), 500);

        assertEquals(List.of(lowerBound, inside, upperBound), timeline.telemetry());
    }

    @Test
    void detectionsAreEmptyAndDetectionRepositoryIsNeverQueriedWhenUsageHasNoStreamId() {
        Instant start = Instant.parse("2026-07-01T00:00:00Z");
        when(usageRepository.findById(usageId)).thenReturn(Optional.of(closedUsage(start, start.plusSeconds(60))));
        when(telemetryRepository.findByUsage(eq(usageId), any(Integer.class)))
                .thenReturn(List.of(sampleAt(start.plusSeconds(10))));

        UsageTimeline timeline = service.timeline(usageId, null, null, 500);

        assertTrue(timeline.detections().isEmpty());
        verifyNoInteractions(detectionRepository);
    }

    @Test
    void emptyHistoryYieldsEmptyTelemetryAndDetections() {
        Instant start = Instant.parse("2026-07-01T00:00:00Z");
        when(usageRepository.findById(usageId)).thenReturn(Optional.of(closedUsage(start, start.plusSeconds(60))));
        when(telemetryRepository.findByUsage(eq(usageId), any(Integer.class))).thenReturn(List.of());

        UsageTimeline timeline = service.timeline(usageId, null, null, 500);

        assertTrue(timeline.telemetry().isEmpty());
        assertTrue(timeline.detections().isEmpty());
    }

    // ---- detections (docs/MVP2-PLAN.md §R, R-a2) ----

    @Test
    void detectionsAreQueriedByTheUsagesStreamIdWithinTheResolvedWindow() {
        Instant start = Instant.parse("2026-07-01T00:00:00Z");
        Instant end = start.plusSeconds(60);
        when(usageRepository.findById(usageId)).thenReturn(Optional.of(closedUsageWithStream(start, end)));
        when(telemetryRepository.findByUsage(eq(usageId), any(Integer.class))).thenReturn(List.of());
        when(detectionRepository.query(any(DetectionQuery.class))).thenReturn(List.of());

        service.timeline(usageId, null, null, 500);

        verify(detectionRepository).query(
                new DetectionQuery(STREAM_ID, start, end, null, DefaultReplayService.DETECTION_FETCH_LIMIT));
    }

    @Test
    void detectionsFromTheRepositoryAreSortedAscendingByCapturedAt() {
        Instant start = Instant.parse("2026-07-01T00:00:00Z");
        Instant end = start.plusSeconds(60);
        when(usageRepository.findById(usageId)).thenReturn(Optional.of(closedUsageWithStream(start, end)));
        when(telemetryRepository.findByUsage(eq(usageId), any(Integer.class))).thenReturn(List.of());

        DetectionResult third = detectionAt(start.plusSeconds(30));
        DetectionResult first = detectionAt(start.plusSeconds(10));
        DetectionResult second = detectionAt(start.plusSeconds(20));
        // newest-first, exactly like the real DetectionRepositoryPort#query contract.
        when(detectionRepository.query(any(DetectionQuery.class))).thenReturn(List.of(third, second, first));

        UsageTimeline timeline = service.timeline(usageId, null, null, 500);

        assertEquals(List.of(first, second, third), timeline.detections());
    }

    @Test
    void detectionsAreThinnedJustLikeTelemetry() {
        Instant start = Instant.parse("2026-07-01T00:00:00Z");
        Instant end = start.plusSeconds(1000);
        when(usageRepository.findById(usageId)).thenReturn(Optional.of(closedUsageWithStream(start, end)));
        when(telemetryRepository.findByUsage(eq(usageId), any(Integer.class))).thenReturn(List.of());

        List<DetectionResult> many = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            many.add(detectionAt(start.plusSeconds(i)));
        }
        when(detectionRepository.query(any(DetectionQuery.class))).thenReturn(many);

        UsageTimeline timeline = service.timeline(usageId, null, null, 5);

        assertEquals(5, timeline.detections().size());
        assertEquals(many.get(0), timeline.detections().get(0));
        assertEquals(many.get(many.size() - 1), timeline.detections().get(timeline.detections().size() - 1));
    }

    // ---- thin() edge cases ----

    @Test
    void thinKeepsEverythingWhenFewerItemsThanMax() {
        List<Integer> items = List.of(1, 2, 3);

        List<Integer> thinned = DefaultReplayService.thin(items, 10);

        assertEquals(items, thinned);
    }

    @Test
    void thinKeepsEverythingWhenExactlyMax() {
        List<Integer> items = List.of(1, 2, 3, 4, 5);

        List<Integer> thinned = DefaultReplayService.thin(items, 5);

        assertEquals(items, thinned);
    }

    @Test
    void thinKeepsFirstAndLastAndIsDeterministicWhenDownsampling() {
        List<Integer> items = List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

        List<Integer> thinned = DefaultReplayService.thin(items, 5);

        // round(i * 9 / 4) for i in 0..4 -> 0, 2, 5(4.5 rounds up), 7, 9
        assertEquals(List.of(0, 2, 5, 7, 9), thinned);
        assertEquals(items.get(0), thinned.get(0));
        assertEquals(items.get(items.size() - 1), thinned.get(thinned.size() - 1));
    }

    @Test
    void thinIsDeterministicAcrossRepeatedCalls() {
        List<Integer> items = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            items.add(i);
        }

        List<Integer> first = DefaultReplayService.thin(items, 37);
        List<Integer> second = DefaultReplayService.thin(items, 37);

        assertEquals(first, second);
        assertEquals(37, first.size());
        assertEquals(Integer.valueOf(0), first.get(0));
        assertEquals(Integer.valueOf(999), first.get(first.size() - 1));
    }

    @Test
    void thinWithMaxPointsOfOneReturnsOnlyFirstItem() {
        List<Integer> items = List.of(1, 2, 3);

        List<Integer> thinned = DefaultReplayService.thin(items, 1);

        assertEquals(List.of(1), thinned);
    }

    @Test
    void thinOnEmptyListReturnsEmptyList() {
        assertEquals(List.of(), DefaultReplayService.thin(List.of(), 500));
    }

    @Test
    void maxPointsIsClampedToCeilingEvenWhenCallerAsksForMore() {
        Instant start = Instant.parse("2026-07-01T00:00:00Z");
        Instant end = start.plusSeconds(10_000);
        when(usageRepository.findById(usageId)).thenReturn(Optional.of(closedUsage(start, end)));

        List<Telemetry> huge = new ArrayList<>();
        for (int i = 0; i < DefaultReplayService.MAX_POINTS_CEILING + 500; i++) {
            huge.add(sampleAt(start.plusSeconds(i)));
        }
        when(telemetryRepository.findByUsage(eq(usageId), any(Integer.class))).thenReturn(huge);

        UsageTimeline timeline = service.timeline(usageId, null, null, 1_000_000);

        assertEquals(DefaultReplayService.MAX_POINTS_CEILING, timeline.telemetry().size());
    }

    // ---- recordingFor (docs/OPS-CORE-PLAN.md §R) ----

    @Test
    void recordingForThrowsNoSuchElementExceptionForUnknownUsage() {
        when(usageRepository.findById(usageId)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.recordingFor(usageId));
    }

    @Test
    void recordingForIsEmptyAndStreamPublisherIsNeverCalledWhenUsageHasNoStreamId() {
        Instant start = Instant.parse("2026-07-01T00:00:00Z");
        when(usageRepository.findById(usageId)).thenReturn(Optional.of(closedUsage(start, start.plusSeconds(60))));

        Optional<UsageRecording> recording = service.recordingFor(usageId);

        assertTrue(recording.isEmpty());
        verifyNoInteractions(streamPublisherPort);
    }

    @Test
    void recordingForIsEmptyWhenStreamPublisherHasNoPlaybackUrl() {
        Instant start = Instant.parse("2026-07-01T00:00:00Z");
        Instant end = start.plusSeconds(60);
        when(usageRepository.findById(usageId)).thenReturn(Optional.of(closedUsageWithStream(start, end)));
        when(streamPublisherPort.playbackUrl(eq(STREAM_ID), eq(start), any(Duration.class)))
                .thenReturn(Optional.empty());

        Optional<UsageRecording> recording = service.recordingFor(usageId);

        assertTrue(recording.isEmpty());
    }

    @Test
    void recordingForResolvesUrlStartAndDurationForAClosedUsage() {
        Instant start = Instant.parse("2026-07-01T00:00:00Z");
        Instant end = start.plusSeconds(90);
        URI url = URI.create("http://localhost:19996/get?path=abc&start=2026-07-01T00%3A00%3A00Z&duration=90");
        when(usageRepository.findById(usageId)).thenReturn(Optional.of(closedUsageWithStream(start, end)));
        when(streamPublisherPort.playbackUrl(STREAM_ID, start, Duration.ofSeconds(90)))
                .thenReturn(Optional.of(url));

        UsageRecording recording = service.recordingFor(usageId).orElseThrow();

        assertEquals(url, recording.url());
        assertEquals(start, recording.start());
        assertEquals(90L, recording.durationSeconds());
    }

    @Test
    void recordingForUsesNowAsTheEndForAnOpenUsage() {
        Instant start = Instant.parse("2026-07-01T00:00:00Z");
        when(usageRepository.findById(usageId)).thenReturn(Optional.of(openUsageWithStream(start)));
        when(streamPublisherPort.playbackUrl(eq(STREAM_ID), eq(start), any(Duration.class)))
                .thenReturn(Optional.of(URI.create("http://localhost:19996/get?path=abc")));

        Instant before = Instant.now();
        UsageRecording recording = service.recordingFor(usageId).orElseThrow();
        Instant after = Instant.now();

        // durationSeconds() truncates to whole seconds, so allow +/-1s slack against the
        // [before, after] window straddling the real "now" the service resolved internally.
        long minExpectedSeconds = Duration.between(start, before).getSeconds() - 1;
        long maxExpectedSeconds = Duration.between(start, after).getSeconds() + 1;
        assertTrue(recording.durationSeconds() >= minExpectedSeconds,
                "expected durationSeconds >= " + minExpectedSeconds + " but was " + recording.durationSeconds());
        assertTrue(recording.durationSeconds() <= maxExpectedSeconds,
                "expected durationSeconds <= " + maxExpectedSeconds + " but was " + recording.durationSeconds());
    }
}

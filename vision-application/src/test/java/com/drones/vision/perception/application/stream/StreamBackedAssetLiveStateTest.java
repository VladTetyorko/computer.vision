package com.drones.vision.perception.application.stream;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.perception.application.pipeline.UsageTracker;
import com.drones.vision.perception.domain.model.DetectionEvent;
import com.drones.vision.perception.domain.model.DetectionEventId;
import com.drones.vision.perception.domain.model.DetectionEventState;
import com.drones.vision.perception.domain.port.DetectionEventRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the runtime-reading logic that moved out of {@code DefaultAssetService}/{@code
 * DefaultDeviceService}/{@code DefaultFleetSummaryService} when {@code AssetLiveStatePort} was
 * inverted onto perception (docs/plans/active/DOMAIN-SEPARATION-W1.md &sect;15, W1.6e).
 */
class StreamBackedAssetLiveStateTest {

    private StreamService streamService;
    private UsageTracker usageTracker;
    private DetectionEventRepositoryPort detectionEventRepositoryPort;
    private StreamBackedAssetLiveState liveState;

    @BeforeEach
    void setUp() {
        streamService = mock(StreamService.class);
        usageTracker = mock(UsageTracker.class);
        detectionEventRepositoryPort = mock(DetectionEventRepositoryPort.class);
        liveState = new StreamBackedAssetLiveState(streamService, usageTracker, detectionEventRepositoryPort);
    }

    @Test
    void activeStreamsByDeviceReflectsEveryRunningStream() {
        DeviceId deviceOne = DeviceId.random();
        DeviceId deviceTwo = DeviceId.random();
        StreamId streamOne = StreamId.random();
        StreamId streamTwo = StreamId.random();
        when(streamService.streams()).thenReturn(List.of(
                new ActiveStream(streamOne, deviceOne, Instant.now()),
                new ActiveStream(streamTwo, deviceTwo, Instant.now())));

        Map<DeviceId, StreamId> byDevice = liveState.activeStreamsByDevice();

        assertEquals(Map.of(deviceOne, streamOne, deviceTwo, streamTwo), byDevice);
    }

    @Test
    void stopStreamsForDevicesStopsOnlyTheNamedDevicesAndCountsThem() {
        DeviceId mine = DeviceId.random();
        StreamId ours = StreamId.random();
        StreamId elsewhere = StreamId.random();
        when(streamService.streams()).thenReturn(List.of(
                new ActiveStream(ours, mine, Instant.now()),
                new ActiveStream(elsewhere, DeviceId.random(), Instant.now())));

        int stopped = liveState.stopStreamsForDevices(List.of(mine));

        assertEquals(1, stopped);
        verify(streamService).stop(ours);
        verify(streamService, never()).stop(elsewhere);
    }

    @Test
    void stopStreamsForDevicesIsANoOpWhenNothingMatches() {
        when(streamService.streams()).thenReturn(List.of());

        int stopped = liveState.stopStreamsForDevices(List.of(DeviceId.random()));

        assertEquals(0, stopped);
        verify(streamService, never()).stop(any());
    }

    @Test
    void latestTelemetryDelegatesToUsageTracker() {
        AssetId assetId = AssetId.random();
        Telemetry sample = new Telemetry(DeviceId.random(), Instant.now(), null, null, null, null, 42.0, Map.of());
        when(usageTracker.latestTelemetry(assetId)).thenReturn(Optional.of(sample));

        assertEquals(Optional.of(sample), liveState.latestTelemetry(assetId));
    }

    @Test
    void latestTelemetryIsEmptyWhenTheAssetHasNeverReported() {
        AssetId assetId = AssetId.random();
        when(usageTracker.latestTelemetry(assetId)).thenReturn(Optional.empty());

        assertTrue(liveState.latestTelemetry(assetId).isEmpty());
    }

    @Test
    void openDetectionEventCountsCountsOnlyOpenEventsPerAsset() {
        AssetId thisAsset = AssetId.random();
        AssetId otherAsset = AssetId.random();
        StreamId streamId = StreamId.random();
        DetectionEvent openForThisAsset = new DetectionEvent(DetectionEventId.random(), streamId, thisAsset,
                "person", 0.9, Instant.now(), Instant.now(), DetectionEventState.OPEN, null);
        DetectionEvent closedForThisAsset = new DetectionEvent(DetectionEventId.random(), streamId, thisAsset,
                "car", 0.9, Instant.now(), Instant.now(), DetectionEventState.CLOSED, null);
        DetectionEvent openForOtherAsset = new DetectionEvent(DetectionEventId.random(), streamId, otherAsset,
                "person", 0.9, Instant.now(), Instant.now(), DetectionEventState.OPEN, null);
        DetectionEvent openWithUnresolvableAsset = new DetectionEvent(DetectionEventId.random(), streamId, null,
                "person", 0.9, Instant.now(), Instant.now(), DetectionEventState.OPEN, null);
        when(detectionEventRepositoryPort.findRecent(any(), anyInt())).thenReturn(
                List.of(openForThisAsset, closedForThisAsset, openForOtherAsset, openWithUnresolvableAsset));

        Map<AssetId, Integer> counts = liveState.openDetectionEventCounts(2000);

        assertEquals(1, counts.get(thisAsset));
        assertEquals(1, counts.get(otherAsset));
        verify(detectionEventRepositoryPort).findRecent(null, 2000);
    }
}

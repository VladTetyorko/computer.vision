package com.drones.vision.perception.application.pipeline;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.events.domain.model.DetectionEvent;
import com.drones.vision.events.domain.model.DetectionEventState;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.perception.domain.model.EventRuleConfig;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.events.domain.port.DetectionEventRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DetectionEventEngineTest {

    private static final ModelRef MODEL = new ModelRef("yolo", "latest");
    private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");

    private final StreamId streamId = StreamId.random();
    private final DeviceId deviceId = DeviceId.random();
    private DetectionEventRepositoryPort eventStore;

    @BeforeEach
    void setUp() {
        eventStore = mock(DetectionEventRepositoryPort.class);
        when(eventStore.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private DetectionEventEngine engine(EventRuleConfig config) {
        return engine(config, null);
    }

    private DetectionEventEngine engine(EventRuleConfig config, UsageTracker usageTracker) {
        return new DetectionEventEngine(streamId, deviceId, config, usageTracker, eventStore);
    }

    private static EventRuleConfig ruleConfig(Set<String> labels, double threshold, int consecutive,
                                               Duration absence) {
        return new EventRuleConfig(labels, threshold, consecutive, absence);
    }

    private DetectionResult result(long sequence, Instant capturedAt, Detection... detections) {
        return new DetectionResult(streamId, sequence, capturedAt, List.of(detections), Duration.ZERO);
    }

    private static Detection detection(String label, double confidence) {
        return new Detection(label, confidence, new BoundingBox(0.1, 0.1, 0.2, 0.2), MODEL);
    }

    @Test
    void opensAnEventAtExactlyNConsecutiveQualifyingResults() {
        EventRuleConfig config = ruleConfig(Set.of("person"), 0.5, 3, Duration.ofSeconds(5));
        DetectionEventEngine engine = engine(config);

        engine.accept(result(0, T0, detection("person", 0.6)));
        engine.accept(result(1, T0.plusMillis(100), detection("person", 0.6)));
        verify(eventStore, never()).save(any());

        engine.accept(result(2, T0.plusMillis(200), detection("person", 0.9)));

        ArgumentCaptor<DetectionEvent> captor = ArgumentCaptor.forClass(DetectionEvent.class);
        verify(eventStore, times(1)).save(captor.capture());
        DetectionEvent opened = captor.getValue();
        assertEquals(DetectionEventState.OPEN, opened.state());
        assertEquals("person", opened.label());
        assertEquals(T0, opened.firstSeen(), "firstSeen must be the streak's first qualifying result");
        assertEquals(T0.plusMillis(200), opened.lastSeen());
        assertEquals(0.9, opened.peakConfidence());
    }

    @Test
    void resetsTheStreakOnAGapRequiringAFreshNConsecutiveAfterward() {
        EventRuleConfig config = ruleConfig(Set.of("person"), 0.5, 3, Duration.ofSeconds(5));
        DetectionEventEngine engine = engine(config);

        engine.accept(result(0, T0, detection("person", 0.6)));
        engine.accept(result(1, T0.plusMillis(100), detection("person", 0.6)));
        engine.accept(result(2, T0.plusMillis(200))); // gap: no qualifying detection this result
        engine.accept(result(3, T0.plusMillis(300), detection("person", 0.6)));
        engine.accept(result(4, T0.plusMillis(400), detection("person", 0.6)));
        verify(eventStore, never()).save(any());

        engine.accept(result(5, T0.plusMillis(500), detection("person", 0.6)));

        verify(eventStore, times(1)).save(any());
    }

    @Test
    void closesAfterAbsenceDurationElapsesButNotBefore() {
        EventRuleConfig config = ruleConfig(Set.of("person"), 0.5, 3, Duration.ofSeconds(5));
        DetectionEventEngine engine = engine(config);

        engine.accept(result(0, T0, detection("person", 0.6)));
        engine.accept(result(1, T0.plusSeconds(1), detection("person", 0.6)));
        engine.accept(result(2, T0.plusSeconds(2), detection("person", 0.6))); // opens here, lastSeen = T0+2s

        // Just under the 5s absence window: must still be open.
        engine.accept(result(3, T0.plusSeconds(2).plusMillis(4999)));
        ArgumentCaptor<DetectionEvent> captor = ArgumentCaptor.forClass(DetectionEvent.class);
        verify(eventStore, times(1)).save(captor.capture());
        assertEquals(DetectionEventState.OPEN, captor.getValue().state());

        // Exactly the 5s absence window: closes.
        engine.accept(result(4, T0.plusSeconds(2).plusSeconds(5)));
        ArgumentCaptor<DetectionEvent> afterClose = ArgumentCaptor.forClass(DetectionEvent.class);
        verify(eventStore, times(2)).save(afterClose.capture());
        DetectionEvent closed = afterClose.getValue();
        assertEquals(DetectionEventState.CLOSED, closed.state());
        assertEquals(T0.plusSeconds(2), closed.lastSeen(), "closing must not move lastSeen forward");
    }

    @Test
    void reopeningAfterCloseRequiresAFreshNConsecutiveStreak() {
        EventRuleConfig config = ruleConfig(Set.of("person"), 0.5, 3, Duration.ofSeconds(5));
        DetectionEventEngine engine = engine(config);

        engine.accept(result(0, T0, detection("person", 0.6)));
        engine.accept(result(1, T0.plusSeconds(1), detection("person", 0.6)));
        engine.accept(result(2, T0.plusSeconds(2), detection("person", 0.6))); // opens
        engine.accept(result(3, T0.plusSeconds(8))); // absent long enough: closes
        verify(eventStore, times(2)).save(any());

        engine.accept(result(4, T0.plusSeconds(9), detection("person", 0.6)));
        engine.accept(result(5, T0.plusSeconds(10), detection("person", 0.6)));
        verify(eventStore, times(2)).save(any()); // still just open+close: not yet 3 fresh results

        engine.accept(result(6, T0.plusSeconds(11), detection("person", 0.6)));
        verify(eventStore, times(3)).save(any()); // reopened
    }

    @Test
    void tracksLabelsIndependently() {
        EventRuleConfig config = ruleConfig(Set.of("person", "car"), 0.5, 2, Duration.ofSeconds(5));
        DetectionEventEngine engine = engine(config);

        engine.accept(result(0, T0, detection("person", 0.6)));
        engine.accept(result(1, T0.plusSeconds(1), detection("person", 0.6))); // person opens

        ArgumentCaptor<DetectionEvent> captor = ArgumentCaptor.forClass(DetectionEvent.class);
        verify(eventStore, times(1)).save(captor.capture());
        assertEquals("person", captor.getValue().label());

        engine.accept(result(2, T0.plusSeconds(2), detection("car", 0.6)));
        engine.accept(result(3, T0.plusSeconds(3), detection("car", 0.6))); // car opens independently

        verify(eventStore, times(2)).save(any());

        // person absent long enough to close (save #3) while this same result's car detection
        // also refreshes car's still-open lastSeen (save #4) — the two labels' state machines
        // never interfere with each other even within the same accept() call.
        engine.accept(result(4, T0.plusSeconds(10), detection("car", 0.6)));

        ArgumentCaptor<DetectionEvent> all = ArgumentCaptor.forClass(DetectionEvent.class);
        verify(eventStore, times(4)).save(all.capture());
        DetectionEvent personClosed = all.getAllValues().stream()
                .filter(e -> e.label().equals("person")).reduce((a, b) -> b).orElseThrow();
        assertEquals(DetectionEventState.CLOSED, personClosed.state());
        assertTrue(all.getAllValues().stream()
                .noneMatch(e -> e.label().equals("car") && e.state() == DetectionEventState.CLOSED));
    }

    @Test
    void confidenceExactlyAtThresholdQualifiesButJustBelowDoesNot() {
        EventRuleConfig config = ruleConfig(Set.of("person"), 0.5, 1, Duration.ofSeconds(5));
        DetectionEventEngine belowThreshold = engine(config);
        belowThreshold.accept(result(0, T0, detection("person", 0.4999)));
        verify(eventStore, never()).save(any());

        DetectionEventEngine atThreshold = engine(config);
        atThreshold.accept(result(0, T0, detection("person", 0.5)));
        verify(eventStore, times(1)).save(any());
    }

    @Test
    void labelsNotInTheConfiguredSetAreNeverTracked() {
        EventRuleConfig config = ruleConfig(Set.of("person"), 0.5, 1, Duration.ofSeconds(5));
        DetectionEventEngine engine = engine(config);

        engine.accept(result(0, T0, detection("dog", 0.99)));

        verify(eventStore, never()).save(any());
    }

    @Test
    void geolocationIsStampedFromUsageTrackerAtOpenTimeWhenAvailable() {
        EventRuleConfig config = ruleConfig(Set.of("person"), 0.5, 1, Duration.ofSeconds(5));
        UsageTracker usageTracker = mock(UsageTracker.class);
        AssetId assetId = AssetId.random();
        GeoPosition position = new GeoPosition(10.0, 20.0, null);
        when(usageTracker.resolveAsset(deviceId)).thenReturn(Optional.of(assetId));
        when(usageTracker.latestPosition(assetId)).thenReturn(Optional.of(position));

        DetectionEventEngine engine = engine(config, usageTracker);
        engine.accept(result(0, T0, detection("person", 0.6)));

        ArgumentCaptor<DetectionEvent> captor = ArgumentCaptor.forClass(DetectionEvent.class);
        verify(eventStore).save(captor.capture());
        assertEquals(assetId, captor.getValue().assetId());
        assertEquals(position, captor.getValue().position());
    }

    @Test
    void geolocationIsNullWhenNoUsageTrackerIsConfigured() {
        EventRuleConfig config = ruleConfig(Set.of("person"), 0.5, 1, Duration.ofSeconds(5));
        DetectionEventEngine engine = engine(config); // no UsageTracker

        engine.accept(result(0, T0, detection("person", 0.6)));

        ArgumentCaptor<DetectionEvent> captor = ArgumentCaptor.forClass(DetectionEvent.class);
        verify(eventStore).save(captor.capture());
        assertNull(captor.getValue().assetId());
        assertNull(captor.getValue().position());
    }

    @Test
    void geolocationIsNullWhenTheDeviceHasNoOwningAsset() {
        EventRuleConfig config = ruleConfig(Set.of("person"), 0.5, 1, Duration.ofSeconds(5));
        UsageTracker usageTracker = mock(UsageTracker.class);
        when(usageTracker.resolveAsset(deviceId)).thenReturn(Optional.empty());

        DetectionEventEngine engine = engine(config, usageTracker);
        engine.accept(result(0, T0, detection("person", 0.6)));

        ArgumentCaptor<DetectionEvent> captor = ArgumentCaptor.forClass(DetectionEvent.class);
        verify(eventStore).save(captor.capture());
        assertNull(captor.getValue().assetId());
        assertNull(captor.getValue().position());
    }

    @Test
    void geolocationIsNullWhenTheAssetHasNoPositionYet() {
        EventRuleConfig config = ruleConfig(Set.of("person"), 0.5, 1, Duration.ofSeconds(5));
        UsageTracker usageTracker = mock(UsageTracker.class);
        AssetId assetId = AssetId.random();
        when(usageTracker.resolveAsset(deviceId)).thenReturn(Optional.of(assetId));
        when(usageTracker.latestPosition(assetId)).thenReturn(Optional.empty());

        DetectionEventEngine engine = engine(config, usageTracker);
        engine.accept(result(0, T0, detection("person", 0.6)));

        ArgumentCaptor<DetectionEvent> captor = ArgumentCaptor.forClass(DetectionEvent.class);
        verify(eventStore).save(captor.capture());
        assertEquals(assetId, captor.getValue().assetId());
        assertNull(captor.getValue().position());
    }

    @Test
    void geolocationIsFrozenAtOpenTimeEvenIfPositionKeepsChangingWhileStillOpen() {
        EventRuleConfig config = ruleConfig(Set.of("person"), 0.5, 1, Duration.ofSeconds(5));
        UsageTracker usageTracker = mock(UsageTracker.class);
        AssetId assetId = AssetId.random();
        GeoPosition openPosition = new GeoPosition(10.0, 20.0, null);
        when(usageTracker.resolveAsset(deviceId)).thenReturn(Optional.of(assetId));
        when(usageTracker.latestPosition(assetId)).thenReturn(Optional.of(openPosition));

        DetectionEventEngine engine = engine(config, usageTracker);
        engine.accept(result(0, T0, detection("person", 0.6))); // opens, stamps openPosition

        // The asset keeps moving after the event opened.
        when(usageTracker.latestPosition(assetId)).thenReturn(Optional.of(new GeoPosition(30.0, 40.0, null)));
        engine.accept(result(1, T0.plusSeconds(1), detection("person", 0.7))); // still-open update

        ArgumentCaptor<DetectionEvent> captor = ArgumentCaptor.forClass(DetectionEvent.class);
        verify(eventStore, times(2)).save(captor.capture());
        assertEquals(openPosition, captor.getValue().position(), "position must stay pinned to open time");
    }
}

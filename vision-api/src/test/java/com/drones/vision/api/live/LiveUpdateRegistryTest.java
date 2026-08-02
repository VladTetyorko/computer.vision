package com.drones.vision.api.live;

import com.drones.vision.api.dto.LiveEnvelopeResponse;
import com.drones.vision.api.dto.MarkPayload;
import com.drones.vision.application.stream.ActiveStream;
import com.drones.vision.application.asset.AssetService;
import com.drones.vision.application.asset.AssetStatus;
import com.drones.vision.application.asset.AssetSummary;
import com.drones.vision.application.device.DeviceService;
import com.drones.vision.application.stream.StreamService;
import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.BoundingBox;
import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.Detection;
import com.drones.vision.domain.model.DetectionEvent;
import com.drones.vision.domain.model.DetectionEventId;
import com.drones.vision.domain.model.DetectionEventState;
import com.drones.vision.domain.model.DetectionResult;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.Event;
import com.drones.vision.domain.model.EventType;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.GeoPosition;
import com.drones.vision.domain.model.LifecycleState;
import com.drones.vision.domain.model.Mark;
import com.drones.vision.domain.model.MarkId;
import com.drones.vision.domain.model.MarkKind;
import com.drones.vision.domain.model.MarkSource;
import com.drones.vision.domain.model.MarkStatus;
import com.drones.vision.domain.model.ModelRef;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.Telemetry;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.DetectionEventRepositoryPort;
import com.drones.vision.domain.port.out.StreamPublisherPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link LiveUpdateRegistry}'s coalescing and resume logic
 * (docs/REALTIME-PLAN.md §4, extended for the {@code devices}/{@code detection-events} topics) — a
 * directly-executing {@link ScheduledExecutorService} test double makes {@link
 * LiveUpdateRegistry#publishFleetChanged()}/{@link LiveUpdateRegistry#publishEvent}/{@link
 * LiveUpdateRegistry#publishDetectionEvent} run synchronously, and {@link
 * LiveUpdateRegistry#flushPending()} is called directly rather than waiting on the real ~150ms
 * timer, so nothing here sleeps or depends on real timing. No Spring context, no {@code
 * SseEmitter} delivery is exercised here — that's {@code LiveControllerTest}'s job (vision-api's
 * {@code com.drones.vision.api} package).
 */
class LiveUpdateRegistryTest {

    private final AssetService assetService = mock(AssetService.class);
    private final DeviceService deviceService = mock(DeviceService.class);
    private final StreamService streamService = mock(StreamService.class);
    private final StreamPublisherPort streamPublisherPort = mock(StreamPublisherPort.class);
    private final DetectionEventRepositoryPort detectionEventRepositoryPort = mock(DetectionEventRepositoryPort.class);

    private LiveUpdateRegistry registry() {
        return new LiveUpdateRegistry(provider(assetService), provider(deviceService), provider(streamService),
                streamPublisherPort, provider(detectionEventRepositoryPort), new ImmediateScheduledExecutorService());
    }

    /** {@link ObjectProvider#getObject()} is a {@code default} method (not abstract), so a plain lambda can't implement it directly -- a minimal override is enough for a test double. */
    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }
        };
    }

    private static AssetSummary summary(AssetId assetId) {
        Asset asset = new Asset(assetId, "drone-1", new CategoryId("drone"),
                new Ownership(UserId.random(), GroupId.random()), Set.of(DeviceId.random()), Map.of());
        return new AssetSummary(asset, "Drone", AssetStatus.OFFLINE, null, null);
    }

    private static Telemetry telemetry(double lat) {
        return new Telemetry(DeviceId.random(), Instant.now(), lat, 10.0, null, null, null, Map.of());
    }

    private static DetectionResult detectionResult(StreamId streamId, long frameSequence) {
        Detection detection = new Detection("person", 0.9, new BoundingBox(0.1, 0.1, 0.2, 0.2),
                new ModelRef("yolo", "latest"));
        return new DetectionResult(streamId, frameSequence, Instant.now(), List.of(detection), Duration.ZERO);
    }

    private static Device device(DeviceId deviceId) {
        return new Device(deviceId, "cam-1", Set.of(), new StreamDescriptor("sim", URI.create("sim://cam-1"), Map.of()),
                LifecycleState.ACTIVE);
    }

    private static DetectionEvent detectionEvent(StreamId streamId, Instant firstSeen) {
        return new DetectionEvent(DetectionEventId.random(), streamId, null, "person", 0.8, firstSeen, firstSeen,
                DetectionEventState.OPEN, null);
    }

    private static Mark mark(MarkStatus status) {
        Ownership ownership = new Ownership(UserId.random(), GroupId.random());
        return new Mark(MarkId.random(), new GeoPosition(50.45, 30.52, null), MarkKind.TARGET, "Bunker", null,
                ownership, Instant.now(), status, MarkSource.MANUAL);
    }

    @Test
    void publishFleetChangedAppendsALiveSnapshotToTheFleetBuffer() {
        when(assetService.assets()).thenReturn(List.of());
        LiveUpdateRegistry registry = registry();

        registry.publishFleetChanged();

        List<LiveEnvelopeResponse> buffered = registry.bufferFor(LiveTopic.FLEET).snapshot();
        assertEquals(1, buffered.size());
        assertEquals("fleet", buffered.get(0).type());
    }

    @Test
    void publishFleetChangedAlsoRefreshesTheDevicesBufferInTheSameDispatch() {
        when(assetService.assets()).thenReturn(List.of());
        DeviceId deviceId = DeviceId.random();
        when(deviceService.devices()).thenReturn(List.of(device(deviceId)));
        StreamId streamId = StreamId.random();
        when(streamService.streams()).thenReturn(List.of(new ActiveStream(streamId, deviceId, Instant.now())));
        when(streamPublisherPort.viewUrl(streamId)).thenReturn(java.util.Optional.of(URI.create("/hls/" + streamId.value())));
        LiveUpdateRegistry registry = registry();

        registry.publishFleetChanged();

        List<LiveEnvelopeResponse> buffered = registry.bufferFor(LiveTopic.DEVICES).snapshot();
        assertEquals(1, buffered.size(), "one devices snapshot per publishFleetChanged() dispatch, same as fleet");
        assertEquals("devices", buffered.get(0).type());
    }

    @Test
    void replayForFleetComputesAFreshLiveSnapshotWhenNothingHasEverBeenPublished() {
        AssetId assetId = AssetId.random();
        when(assetService.assets()).thenReturn(List.of(summary(assetId)));
        LiveUpdateRegistry registry = registry();

        // Nothing was ever published -- the fleet buffer starts empty, so replaying it (fresh
        // connect, no Last-Event-ID) must fall back to a real live query rather than silently
        // reporting "no assets" just because the process just started.
        List<LiveEnvelopeResponse> replayed = registry.replayFor(LiveTopic.FLEET, null);

        assertEquals(1, replayed.size());
        assertEquals("fleet", replayed.get(0).type());
        @SuppressWarnings("unchecked")
        List<Object> payload = (List<Object>) replayed.get(0).payload();
        assertEquals(1, payload.size());
    }

    @Test
    void replayForDevicesComputesAFreshLiveSnapshotWhenNothingHasEverBeenPublished() {
        DeviceId deviceId = DeviceId.random();
        when(deviceService.devices()).thenReturn(List.of(device(deviceId)));
        when(streamService.streams()).thenReturn(List.of());
        LiveUpdateRegistry registry = registry();

        List<LiveEnvelopeResponse> replayed = registry.replayFor(LiveTopic.DEVICES, null);

        assertEquals(1, replayed.size(), "devices gets the same empty-buffer live-query fallback as fleet");
        assertEquals("devices", replayed.get(0).type());
    }

    @Test
    void replayForDetectionEventsSeedsFromTheRepositoryWhenBufferIsEmpty() {
        StreamId streamId = StreamId.random();
        DetectionEvent older = detectionEvent(streamId, Instant.now().minusSeconds(10));
        DetectionEvent newer = detectionEvent(streamId, Instant.now());
        // findRecent's own contract is newest-first -- the registry must reverse this before
        // appending so the buffer's causal/seq ordering stays oldest-first like every other append.
        when(detectionEventRepositoryPort.findRecent(null, LiveUpdateRegistry.DETECTION_EVENT_BUFFER_CAPACITY))
                .thenReturn(List.of(newer, older));
        LiveUpdateRegistry registry = registry();

        List<LiveEnvelopeResponse> replayed = registry.replayFor(LiveTopic.DETECTION_EVENTS, null);

        assertEquals(2, replayed.size());
        assertTrue(replayed.get(0).seq() < replayed.get(1).seq(), "seeded oldest-first");
        assertEquals("detection-events", replayed.get(0).type());
    }

    @Test
    void replayForDetectionEventsReturnsEmptyWhenTheRepositoryHasNothingRecent() {
        when(detectionEventRepositoryPort.findRecent(null, LiveUpdateRegistry.DETECTION_EVENT_BUFFER_CAPACITY))
                .thenReturn(List.of());
        LiveUpdateRegistry registry = registry();

        assertEquals(List.of(), registry.replayFor(LiveTopic.DETECTION_EVENTS, null));
    }

    @Test
    void publishDetectionEventAppendsImmediatelyWithoutWaitingForAFlush() {
        LiveUpdateRegistry registry = registry();

        registry.publishDetectionEvent(detectionEvent(StreamId.random(), Instant.now()));

        List<LiveEnvelopeResponse> buffered = registry.bufferFor(LiveTopic.DETECTION_EVENTS).snapshot();
        assertEquals(1, buffered.size());
        assertEquals("detection-events", buffered.get(0).type());
    }

    @Test
    void publishMarkCreatedAppendsAMarksEnvelopeWithActionCreated() {
        LiveUpdateRegistry registry = registry();
        Mark created = mark(MarkStatus.ACTIVE);

        registry.publishMarkCreated(created);

        List<LiveEnvelopeResponse> buffered = registry.bufferFor(LiveTopic.MARKS).snapshot();
        assertEquals(1, buffered.size());
        assertEquals("marks", buffered.get(0).type());
        MarkPayload payload = (MarkPayload) buffered.get(0).payload();
        assertEquals("created", payload.action());
        assertEquals(created.id().value().toString(), payload.mark().id());
        assertEquals("ACTIVE", payload.mark().status());
    }

    @Test
    void publishMarkUpdatedAppendsAMarksEnvelopeWithActionUpdated() {
        LiveUpdateRegistry registry = registry();
        Mark updated = mark(MarkStatus.ACTIVE);

        registry.publishMarkUpdated(updated);

        List<LiveEnvelopeResponse> buffered = registry.bufferFor(LiveTopic.MARKS).snapshot();
        assertEquals(1, buffered.size());
        assertEquals("marks", buffered.get(0).type());
        MarkPayload payload = (MarkPayload) buffered.get(0).payload();
        assertEquals("updated", payload.action());
        assertEquals("ACTIVE", payload.mark().status(), "annotation-only updates leave status untouched");
    }

    @Test
    void publishMarkClearedForcesStatusClearedInThePayloadEvenWhenTheMarkPassedInIsStillActive() {
        LiveUpdateRegistry registry = registry();
        // The delete path (DefaultMarkService#delete) publishes the pre-deletion Mark, whose status
        // may still be ACTIVE -- the wire contract requires the envelope to present "CLEARED"
        // regardless, so a client can resolve which pin to drop without a second lookup.
        Mark stillActive = mark(MarkStatus.ACTIVE);

        registry.publishMarkCleared(stillActive);

        List<LiveEnvelopeResponse> buffered = registry.bufferFor(LiveTopic.MARKS).snapshot();
        assertEquals(1, buffered.size());
        MarkPayload payload = (MarkPayload) buffered.get(0).payload();
        assertEquals("cleared", payload.action());
        assertEquals("CLEARED", payload.mark().status(), "cleared must force status=CLEARED even for a still-ACTIVE input Mark");
        assertEquals(stillActive.id().value().toString(), payload.mark().id());
    }

    @Test
    void publishMarkClearedKeepsStatusClearedWhenTheMarkPassedInIsAlreadyCleared() {
        LiveUpdateRegistry registry = registry();
        Mark alreadyCleared = mark(MarkStatus.CLEARED);

        registry.publishMarkCleared(alreadyCleared);

        MarkPayload payload = (MarkPayload) registry.bufferFor(LiveTopic.MARKS).snapshot().get(0).payload();
        assertEquals("CLEARED", payload.mark().status());
    }

    @Test
    void marksTopicIsHonestlyLimitedAndHasNothingToReplayWhenNothingHasEverBeenPublished() {
        LiveUpdateRegistry registry = registry();

        // Deliberate, documented gap: unlike fleet/devices/detection-events, marks has no
        // live-query seed (would need a sixth constructor parameter -- see class javadoc). A
        // viewer's first connection instead relies on its own GET /api/marks read.
        assertEquals(List.of(), registry.replayFor(LiveTopic.MARKS, null));
    }

    @Test
    void telemetryIsCoalescedIntoOneEnvelopePerAssetOnFlush() {
        AssetId assetId = AssetId.random();
        LiveUpdateRegistry registry = registry();

        registry.publishTelemetryAppended(assetId, telemetry(1.0));
        registry.publishTelemetryAppended(assetId, telemetry(2.0));
        registry.publishTelemetryAppended(assetId, telemetry(3.0));
        // Nothing reaches the buffer until a flush actually runs.
        assertTrue(registry.bufferFor(LiveTopic.telemetry(assetId)).isEmpty());

        registry.flushPending();

        List<LiveEnvelopeResponse> buffered = registry.bufferFor(LiveTopic.telemetry(assetId)).snapshot();
        assertEquals(1, buffered.size(), "three appended samples must coalesce into exactly one envelope");
        assertEquals("telemetry", buffered.get(0).type());
        assertEquals(assetId.value().toString(), buffered.get(0).assetId());
        @SuppressWarnings("unchecked")
        List<Object> payload = (List<Object>) buffered.get(0).payload();
        assertEquals(3, payload.size(), "the coalesced payload must carry every sample appended since the last flush");
    }

    @Test
    void telemetryForDifferentAssetsCoalescesIntoIndependentEnvelopes() {
        AssetId assetA = AssetId.random();
        AssetId assetB = AssetId.random();
        LiveUpdateRegistry registry = registry();

        registry.publishTelemetryAppended(assetA, telemetry(1.0));
        registry.publishTelemetryAppended(assetB, telemetry(2.0));
        registry.publishTelemetryAppended(assetB, telemetry(3.0));
        registry.flushPending();

        assertEquals(1, registry.bufferFor(LiveTopic.telemetry(assetA)).snapshot().size());
        assertEquals(1, registry.bufferFor(LiveTopic.telemetry(assetB)).snapshot().size());
    }

    @Test
    void aFlushWithNothingPendingForAnAssetAppendsNothingNew() {
        AssetId assetId = AssetId.random();
        LiveUpdateRegistry registry = registry();
        registry.publishTelemetryAppended(assetId, telemetry(1.0));
        registry.flushPending();
        assertEquals(1, registry.bufferFor(LiveTopic.telemetry(assetId)).snapshot().size());

        registry.flushPending(); // nothing new was published since the last flush

        assertEquals(1, registry.bufferFor(LiveTopic.telemetry(assetId)).snapshot().size());
    }

    @Test
    void detectionsAreLatestOnlyNotAccumulatedAcrossAFlushWindow() {
        AssetId assetId = AssetId.random();
        StreamId streamId = StreamId.random();
        LiveUpdateRegistry registry = registry();

        registry.publishDetections(assetId, detectionResult(streamId, 0));
        registry.publishDetections(assetId, detectionResult(streamId, 1));
        DetectionResult latest = detectionResult(streamId, 2);
        registry.publishDetections(assetId, latest);

        registry.flushPending();

        List<LiveEnvelopeResponse> buffered = registry.bufferFor(LiveTopic.detections(assetId)).snapshot();
        assertEquals(1, buffered.size(), "detections emit latest-frame-only -- no backlog");
        assertEquals("detections", buffered.get(0).type());
    }

    @Test
    void publishEventAppendsImmediatelyWithoutWaitingForAFlush() {
        LiveUpdateRegistry registry = registry();

        registry.publishEvent(Event.of(StreamId.random(), EventType.STREAM_STARTED, "started"));

        assertEquals(1, registry.bufferFor(LiveTopic.EVENT).snapshot().size());
    }

    @Test
    void replayForATopicWithNoGapReturnsOnlyEntriesAfterTheGivenSeq() {
        AssetId assetId = AssetId.random();
        LiveUpdateRegistry registry = registry();
        registry.publishTelemetryAppended(assetId, telemetry(1.0));
        registry.flushPending();
        long firstSeq = registry.bufferFor(LiveTopic.telemetry(assetId)).snapshot().get(0).seq();

        registry.publishTelemetryAppended(assetId, telemetry(2.0));
        registry.flushPending();

        List<LiveEnvelopeResponse> resumed = registry.replayFor(LiveTopic.telemetry(assetId), firstSeq);
        assertEquals(1, resumed.size(), "resuming from the first envelope's own seq must skip it, keep only what's newer");
    }

    @Test
    void replayForATopicWithNothingBufferedYetReturnsAnEmptyList() {
        LiveUpdateRegistry registry = registry();

        assertEquals(List.of(), registry.replayFor(LiveTopic.telemetry(AssetId.random()), null));
        assertEquals(List.of(), registry.replayFor(LiveTopic.telemetry(AssetId.random()), 5L));
    }

    @Test
    void updateTopicsThrowsForAnUnknownConnectionId() {
        LiveUpdateRegistry registry = registry();

        assertThrows(NoSuchElementException.class,
                () -> registry.updateTopics("no-such-connection", com.drones.vision.api.dto.UpdateLiveTopicsRequest.EMPTY));
    }

    /**
     * Runs every submitted/scheduled task synchronously, on the calling thread, the moment it's
     * submitted — makes {@link LiveUpdateRegistry#publishFleetChanged()}/{@link
     * LiveUpdateRegistry#publishEvent}/{@link LiveUpdateRegistry#publishDetectionEvent}
     * deterministic in a pure unit test with no real waiting, and makes the constructor's own
     * {@code scheduleAtFixedRate} calls (the periodic flush/heartbeat) a harmless no-op (this fake
     * never actually re-invokes a periodic task on its own).
     */
    private static final class ImmediateScheduledExecutorService implements ScheduledExecutorService {
        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            return null; // never actually re-ticks -- tests call flushPending()/heartbeatAll() directly
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return null;
        }

        @Override
        public <V> ScheduledFuture<V> schedule(java.util.concurrent.Callable<V> callable, long delay, TimeUnit unit) {
            return null;
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            return null;
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(Runnable task, T result) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.concurrent.Future<?> submit(Runnable task) {
            task.run();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(
                java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(
                java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T invokeAny(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T invokeAny(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks, long timeout,
                                TimeUnit unit) {
            throw new UnsupportedOperationException();
        }
    }
}

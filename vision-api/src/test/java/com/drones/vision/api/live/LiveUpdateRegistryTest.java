package com.drones.vision.api.live;

import com.drones.vision.api.dto.LiveEnvelopeResponse;
import com.drones.vision.api.dto.MapEventPayload;
import com.drones.vision.perception.application.stream.ActiveStream;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.asset.AssetStatus;
import com.drones.vision.warehouse.application.asset.AssetSummary;
import com.drones.vision.warehouse.application.device.DeviceService;
import com.drones.vision.perception.application.stream.StreamService;
import com.drones.vision.map.domain.model.AccessLevel;
import com.drones.vision.map.domain.model.Affiliation;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.events.domain.model.DetectionEvent;
import com.drones.vision.events.domain.model.DetectionEventId;
import com.drones.vision.events.domain.model.DetectionEventState;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.map.domain.model.DrawKind;
import com.drones.vision.map.domain.model.Drawing;
import com.drones.vision.map.domain.model.DrawingId;
import com.drones.vision.platform.Event;
import com.drones.vision.platform.EventType;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.map.domain.model.LayerGrant;
import com.drones.vision.map.domain.model.LayerId;
import com.drones.vision.map.domain.model.LayerKind;
import com.drones.vision.kernel.LifecycleState;
import com.drones.vision.map.domain.model.MapEvent;
import com.drones.vision.map.domain.model.MapLayer;
import com.drones.vision.map.domain.model.Mark;
import com.drones.vision.map.domain.model.MarkId;
import com.drones.vision.map.domain.model.MarkKind;
import com.drones.vision.map.domain.model.MarkSource;
import com.drones.vision.map.domain.model.MarkStatus;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.flight.domain.model.Telemetry;
import com.drones.vision.kernel.UserId;
import com.drones.vision.map.domain.model.Verification;
import com.drones.vision.events.domain.port.DetectionEventRepositoryPort;
import com.drones.vision.perception.domain.port.StreamPublisherPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link LiveUpdateRegistry}'s coalescing and resume logic
 * (docs/plans/done/REALTIME-PLAN.md §4, extended for the {@code devices}/{@code detection-events} topics) — a
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

    private static Mark mark(MarkStatus status, LayerId layerId) {
        Ownership ownership = new Ownership(UserId.random(), GroupId.random());
        return new Mark(MarkId.random(), layerId, new GeoPosition(50.45, 30.52, null), MarkKind.TARGET,
                Affiliation.HOSTILE, "Bunker", null, ownership, Instant.now(), status, MarkSource.MANUAL,
                Verification.unverified());
    }

    private static Drawing drawing(LayerId layerId) {
        return new Drawing(DrawingId.random(), layerId, DrawKind.LINE,
                List.of(new GeoPosition(50.0, 30.0, null), new GeoPosition(50.5, 30.5, null)),
                null, null, new Ownership(UserId.random(), GroupId.random()), Instant.now());
    }

    private static MapLayer layer(LayerId layerId) {
        return new MapLayer(layerId, "Bravo team", LayerKind.TEAM,
                new Ownership(UserId.random(), GroupId.random()),
                List.of(new LayerGrant(LayerGrant.SubjectType.USER, UUID.randomUUID(), AccessLevel.VIEW)),
                Instant.now());
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
    void publishMapEventAppendsAMapEnvelopeCarryingEntityActionAndLayerId() {
        LiveUpdateRegistry registry = registry();
        LayerId layerId = LayerId.random();
        Mark created = mark(MarkStatus.ACTIVE, layerId);

        registry.publishMapEvent(new MapEvent(MapEvent.EntityType.MARK, MapEvent.Action.CREATED, layerId, created));

        List<LiveEnvelopeResponse> buffered = registry.bufferFor(LiveTopic.MAP).snapshot();
        assertEquals(1, buffered.size());
        assertEquals("map", buffered.get(0).type());
        MapEventPayload payload = (MapEventPayload) buffered.get(0).payload();
        assertEquals("mark", payload.entity());
        assertEquals("created", payload.action());
        assertEquals(layerId.value().toString(), payload.layerId());
        assertEquals(created.id().value().toString(), payload.mark().markId());
        assertEquals("ACTIVE", payload.mark().status());
        assertNull(payload.drawing());
        assertNull(payload.layer());
    }

    @Test
    void publishMapEventCarriesAClearedMarkAsIsWithoutForcingItsStatus() {
        LiveUpdateRegistry registry = registry();
        LayerId layerId = LayerId.random();
        Mark cleared = mark(MarkStatus.CLEARED, layerId);

        registry.publishMapEvent(new MapEvent(MapEvent.EntityType.MARK, MapEvent.Action.CLEARED, layerId, cleared));

        MapEventPayload payload = (MapEventPayload) registry.bufferFor(LiveTopic.MAP).snapshot().get(0).payload();
        assertEquals("cleared", payload.action());
        assertEquals("CLEARED", payload.mark().status());
    }

    @Test
    void publishMapEventMapsADrawingIntoTheDrawingFieldOnly() {
        LiveUpdateRegistry registry = registry();
        LayerId layerId = LayerId.random();
        Drawing drawn = drawing(layerId);

        registry.publishMapEvent(
                new MapEvent(MapEvent.EntityType.DRAWING, MapEvent.Action.UPDATED, layerId, drawn));

        MapEventPayload payload = (MapEventPayload) registry.bufferFor(LiveTopic.MAP).snapshot().get(0).payload();
        assertEquals("drawing", payload.entity());
        assertEquals("updated", payload.action());
        assertEquals(drawn.id().value().toString(), payload.drawing().drawingId());
        assertNull(payload.mark());
        assertNull(payload.layer());
    }

    @Test
    void publishMapEventNeverPutsALayersGrantsOnTheWire() {
        LiveUpdateRegistry registry = registry();
        LayerId layerId = LayerId.random();
        MapLayer granted = layer(layerId);
        assertEquals(1, granted.grants().size(), "fixture must actually carry a grant");

        registry.publishMapEvent(
                new MapEvent(MapEvent.EntityType.LAYER, MapEvent.Action.UPDATED, layerId, granted));

        MapEventPayload payload = (MapEventPayload) registry.bufferFor(LiveTopic.MAP).snapshot().get(0).payload();
        assertEquals("layer", payload.entity());
        assertEquals("Bravo team", payload.layer().name());
        assertNull(payload.layer().grants(),
                "docs/plans/done/MAP-REWORK-PLAN.md §4.3: layer events over SSE never include grants");
        assertNull(payload.layer().myAccess(), "myAccess is per-viewer and has no single value on a broadcast");
    }

    @Test
    void mapTopicIsHonestlyLimitedAndHasNothingToReplayWhenNothingHasEverBeenPublished() {
        LiveUpdateRegistry registry = registry();

        // Deliberate, documented gap: unlike fleet/devices/detection-events, map has no live-query
        // seed -- it would need two more constructor parameters AND could not be scoped per
        // recipient from one shared buffer (see class javadoc). A viewer's first connection instead
        // relies on its own GET /api/map/* reads, which are already scoped correctly.
        assertEquals(List.of(), registry.replayFor(LiveTopic.MAP, null));
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

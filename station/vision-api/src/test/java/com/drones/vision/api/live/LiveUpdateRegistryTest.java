package com.drones.vision.api.live;

import com.drones.vision.api.dto.AssetSummaryResponse;
import com.drones.vision.api.dto.LiveEnvelopeResponse;
import com.drones.vision.api.dto.MapEventPayload;
import com.drones.vision.api.support.VisionApiProperties;
import com.drones.vision.perception.application.stream.ActiveStream;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.asset.AssetStatus;
import com.drones.vision.warehouse.application.asset.AssetSummary;
import com.drones.vision.warehouse.application.device.DeviceService;
import com.drones.vision.perception.application.stream.StreamService;
import com.drones.vision.map.domain.model.AccessLevel;
import com.drones.vision.map.domain.model.Affiliation;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.Identity;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.perception.domain.model.DetectionEvent;
import com.drones.vision.perception.domain.model.DetectionEventId;
import com.drones.vision.perception.domain.model.DetectionEventState;
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
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.kernel.UserId;
import com.drones.vision.map.domain.model.Verification;
import com.drones.vision.perception.domain.port.DetectionEventRepositoryPort;
import com.drones.vision.perception.domain.port.StreamPublisherPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        Asset asset = Asset.register(assetId, "drone-1", new CategoryId("drone"),
                new Ownership(UserId.random(), GroupId.random()), Set.of(DeviceId.random()), Map.of(), Identity.NONE,
                Custody.NONE);
        return new AssetSummary(asset, "Drone", AssetStatus.OFFLINE, null, null, asset.inventoryState(),
                asset.identity(), asset.custody());
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

    /**
     * docs/plans/done/SCALE-100-PLAN.md §5 S5 — {@link LiveUpdateRegistry#publishFleetChanged()} used to
     * recompute the entire fleet+devices snapshot on every single call; it now coalesces leading+
     * trailing, the same treatment {@link LiveUpdateRegistry#flushPending()} already gives telemetry/
     * detections. These three tests exercise the coalescing itself, independently of the class's
     * other tests above (which only ever call it once per registry and so never observe a window).
     */
    @Test
    void aFlushWithNoCoalescedFleetChangeDoesNotTriggerAnExtraRecompute() {
        when(assetService.assets()).thenReturn(List.of());
        LiveUpdateRegistry registry = registry();

        registry.publishFleetChanged(); // the lone, immediate leading-edge dispatch
        registry.flushPending(); // must be a no-op here -- nothing was coalesced away to catch up on

        verify(assetService, times(1)).assets();
    }

    @Test
    void fiftyRapidPublishFleetChangedCallsCoalesceIntoAtMostTwoRecomputes() {
        when(assetService.assets()).thenReturn(List.of());
        LiveUpdateRegistry registry = registry();

        for (int i = 0; i < 50; i++) {
            registry.publishFleetChanged();
        }
        // All 50 calls land inside the same coalescing window (this tight loop lets no real time
        // elapse) -- only the first opens the window and dispatches; the other 49 just mark it dirty.
        verify(assetService, times(1)).assets();

        registry.flushPending(); // simulates the window closing -- pays off the coalesced 49

        verify(assetService, times(2)).assets();
    }

    @Test
    void theTrailingRecomputeAfterACoalescedBurstReflectsTheNewestStateNotTheFirst() {
        AssetId staleAssetId = AssetId.random();
        AssetId freshAssetId = AssetId.random();
        when(assetService.assets()).thenReturn(List.of(summary(staleAssetId)));
        LiveUpdateRegistry registry = registry();

        registry.publishFleetChanged(); // leading-edge dispatch -- captures the stale state immediately
        // A further write commits to the underlying service before the coalescing window closes --
        // CLAUDE.md rule 9 ("newest data wins"): the trailing recompute must pick this up, not
        // silently keep serving what the leading dispatch already captured.
        when(assetService.assets()).thenReturn(List.of(summary(freshAssetId)));
        registry.publishFleetChanged(); // coalesced away -- only marks the change pending

        registry.flushPending(); // the trailing catch-up

        List<LiveEnvelopeResponse> buffered = registry.bufferFor(LiveTopic.FLEET).snapshot();
        assertEquals(1, buffered.size(), "fleet is latest-only -- the trailing recompute replaces the leading one");
        @SuppressWarnings("unchecked")
        List<AssetSummaryResponse> payload = (List<AssetSummaryResponse>) (List<?>) buffered.get(0).payload();
        assertEquals(1, payload.size());
        assertEquals(freshAssetId.value().toString(), payload.get(0).assetId(),
                "the trailing recompute must reflect the newest write, never the one the leading dispatch captured");
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

    /**
     * docs/plans/done/SCALE-100-PLAN.md §5 S7: {@code telemetryBuffer} is no longer the private
     * {@code static final TELEMETRY_BUFFER_CAPACITY} constant it used to be — it comes from the
     * {@link VisionApiProperties.Live} passed to the full constructor. This proves the value is
     * actually enforced, not just stored: with a capacity of 2, a third coalesced flush must evict
     * the oldest envelope rather than growing the buffer past what was configured.
     */
    @Test
    void configuredTelemetryBufferCapacityActuallyBoundsTheRingBufferSize() {
        VisionApiProperties.Live defaults = VisionApiProperties.Live.defaults();
        VisionApiProperties.Live smallTelemetryBuffer = new VisionApiProperties.Live(defaults.coalesce(),
                defaults.heartbeat(), 2, defaults.eventBuffer(), defaults.detectionBuffer(), defaults.mapBuffer(),
                defaults.sendTimeout(), defaults.bufferEviction());
        LiveUpdateRegistry registry = new LiveUpdateRegistry(provider(assetService), provider(deviceService),
                provider(streamService), streamPublisherPort, provider(detectionEventRepositoryPort),
                smallTelemetryBuffer, new ImmediateScheduledExecutorService());
        AssetId assetId = AssetId.random();

        for (double lat = 1.0; lat <= 3.0; lat++) {
            registry.publishTelemetryAppended(assetId, telemetry(lat));
            registry.flushPending();
        }

        assertEquals(2, registry.bufferFor(LiveTopic.telemetry(assetId)).snapshot().size(),
                "the configured capacity of 2 must be enforced, not the old default of 50");
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
                () -> registry.updateTopics("no-such-connection", com.drones.vision.api.dto.UpdateLiveTopicsRequest.EMPTY,
                        UserId.random()));
    }

    @Test
    void watchingDetectionsIsFalseWhenNoConnectionIsSubscribed() {
        LiveUpdateRegistry registry = registry();

        assertEquals(false, registry.watchingDetections(AssetId.random()));
    }

    @Test
    void watchingDetectionsIsTrueOnceAConnectionSubscribesToThatAssetsDetectionsTopic() {
        // docs/plans/done/CV-DEMAND-PLAN.md §3.5's SSE half: a cockpit open on this asset
        // subscribes to `detections:<assetId>` at connect time, and that alone is "someone is
        // watching" -- no envelope needs to actually flow.
        AssetId assetId = AssetId.random();
        AssetId otherAssetId = AssetId.random();
        when(assetService.assets()).thenReturn(List.of());
        LiveUpdateRegistry registry = registry();

        registry.connect("detections:" + assetId.value(), null, UserId.random(), layerId -> true, id -> true);

        assertEquals(true, registry.watchingDetections(assetId));
        assertEquals(false, registry.watchingDetections(otherAssetId), "only the subscribed asset counts");
    }

    /**
     * S2 (docs/plans/done/SCALE-100-PLAN.md §5) — {@link #register} plugs a test-double {@link SseEmitter}
     * straight into the registry, so these tests can observe exactly what {@link
     * LiveUpdateRegistry#broadcast}/{@link LiveUpdateRegistry#heartbeatAll} actually write, and with
     * what timing, on {@link LiveUpdateRegistry#connectionWriteExecutor}'s real virtual threads —
     * unlike every test above, {@code publishXxx}'s coalesce/broadcast decision still runs
     * synchronously via {@link ImmediateScheduledExecutorService}, but the connection write itself
     * is genuinely asynchronous here, exactly as it is in production.
     */
    @Test
    void aBlockedConnectionDoesNotStopOthersFromReceivingEnvelopes() {
        LiveUpdateRegistry registry = registry();
        CountDownLatch neverReleased = new CountDownLatch(1);
        registry.register(new BlockingSseEmitter(neverReleased), Set.of(LiveTopic.EVENT), UserId.random(), layerId -> true,
                id -> true);
        RecordingSseEmitter first = new RecordingSseEmitter();
        RecordingSseEmitter second = new RecordingSseEmitter();
        registry.register(first, Set.of(LiveTopic.EVENT), UserId.random(), layerId -> true, id -> true);
        registry.register(second, Set.of(LiveTopic.EVENT), UserId.random(), layerId -> true, id -> true);

        registry.publishEvent(Event.of(StreamId.random(), EventType.STREAM_STARTED, "started"));

        try {
            assertTrue(awaitTrue(Duration.ofSeconds(2), () -> first.received().size() == 1 && second.received().size() == 1),
                    "the two healthy connections must receive the envelope despite the third connection's write being stuck");
        } finally {
            neverReleased.countDown(); // release the blocked virtual thread so it doesn't leak past this test
        }
    }

    @Test
    void aConnectionWhoseWriteStaysBlockedPastTheTimeoutIsUnregistered() {
        LiveUpdateRegistry registry = registry();
        CountDownLatch neverReleased = new CountDownLatch(1);
        AssetId assetId = AssetId.random();
        registry.register(new BlockingSseEmitter(neverReleased), Set.of(LiveTopic.EVENT, LiveTopic.detections(assetId)),
                UserId.random(), layerId -> true, id -> true);
        assertTrue(registry.watchingDetections(assetId), "sanity: the connection is registered and subscribed before anything blocks");

        registry.publishEvent(Event.of(StreamId.random(), EventType.STREAM_STARTED, "started"));

        try {
            assertTrue(awaitTrue(Duration.ofMillis(LiveUpdateRegistry.CONNECTION_WRITE_TIMEOUT_MILLIS + 2_000),
                            () -> !registry.watchingDetections(assetId)),
                    "a connection whose write never completes must be unregistered once the write timeout elapses");
        } finally {
            neverReleased.countDown();
        }
    }

    @Test
    void concurrentDispatchNeverReordersOneConnectionsOwnEnvelopes() {
        LiveUpdateRegistry registry = registry();
        int connectionCount = 20;
        int envelopeCount = 15;
        List<RecordingSseEmitter> emitters = new ArrayList<>();
        for (int i = 0; i < connectionCount; i++) {
            RecordingSseEmitter emitter = new RecordingSseEmitter();
            emitters.add(emitter);
            registry.register(emitter, Set.of(LiveTopic.EVENT), UserId.random(), layerId -> true, id -> true);
        }

        for (int i = 0; i < envelopeCount; i++) {
            registry.publishEvent(Event.of(StreamId.random(), EventType.STREAM_STARTED, "evt-" + i));
        }

        for (RecordingSseEmitter emitter : emitters) {
            assertTrue(awaitTrue(Duration.ofSeconds(2), () -> emitter.received().size() == envelopeCount),
                    "every connection must eventually receive every envelope");
            List<Long> seqs = emitter.received().stream().map(LiveUpdateRegistryTest::seqOf).toList();
            List<Long> sortedSeqs = seqs.stream().sorted().toList();
            assertEquals(sortedSeqs, seqs,
                    "one connection's own writes must never be reordered by concurrent dispatch, even under jitter");
        }
    }

    @Test
    void resumeStaysCorrectWhileAnotherConnectionsWriteIsStuckInFlight() {
        LiveUpdateRegistry registry = registry();
        CountDownLatch neverReleased = new CountDownLatch(1);
        registry.register(new BlockingSseEmitter(neverReleased), Set.of(LiveTopic.EVENT), UserId.random(), layerId -> true,
                id -> true);

        registry.publishEvent(Event.of(StreamId.random(), EventType.STREAM_STARTED, "first"));
        long firstSeq = registry.bufferFor(LiveTopic.EVENT).snapshot().get(0).seq();

        try {
            // The blocked connection's write for "first" is now queued on connectionWriteExecutor
            // and will never complete -- exactly the risk docs/plans/done/SCALE-100-PLAN.md §9 calls out
            // ("multi-threaded dispatch reorders envelopes within a topic"). Two more envelopes are
            // published while that write is stuck in flight.
            registry.publishEvent(Event.of(StreamId.random(), EventType.STREAM_STOPPED, "second"));
            registry.publishEvent(Event.of(StreamId.random(), EventType.STREAM_STOPPED, "third"));

            List<LiveEnvelopeResponse> resumed = registry.replayFor(LiveTopic.EVENT, firstSeq);
            assertEquals(2, resumed.size(), "resume must see exactly what's newer, unaffected by the stuck write");
            assertTrue(resumed.get(0).seq() < resumed.get(1).seq(), "resumed entries must stay in seq order");
            assertEquals(firstSeq + 1, resumed.get(0).seq());
            assertEquals(firstSeq + 2, resumed.get(1).seq());
        } finally {
            neverReleased.countDown();
        }
    }

    @Test
    void evictUnusedAssetBuffersRemovesABufferNoConnectionSubscribesToAnymore() {
        AssetId assetId = AssetId.random();
        LiveUpdateRegistry registry = registry();
        registry.publishTelemetryAppended(assetId, telemetry(1.0));
        registry.flushPending();
        assertEquals(1, registry.bufferFor(LiveTopic.telemetry(assetId)).snapshot().size(),
                "sanity: the buffer holds the flushed sample");

        registry.evictUnusedAssetBuffers();

        assertTrue(registry.bufferFor(LiveTopic.telemetry(assetId)).isEmpty(),
                "bufferFor recreates an empty buffer on demand -- proof the evicted one (and its sample) is actually "
                        + "gone, not merely still sitting in the map");
    }

    @Test
    void evictUnusedAssetBuffersLeavesAnActivelySubscribedAssetsBufferAlone() {
        AssetId assetId = AssetId.random();
        when(assetService.assets()).thenReturn(List.of());
        LiveUpdateRegistry registry = registry();
        registry.connect("telemetry:" + assetId.value(), null, UserId.random(), layerId -> true, id -> true);
        registry.publishTelemetryAppended(assetId, telemetry(1.0));
        registry.flushPending();

        registry.evictUnusedAssetBuffers();

        assertEquals(1, registry.bufferFor(LiveTopic.telemetry(assetId)).snapshot().size(),
                "an asset's buffer must survive eviction while a connection is still subscribed to it");
    }

    private static long seqOf(String json) {
        return new JsonMapper().readTree(json).get("seq").asLong();
    }

    /** Polls {@code condition} every 20ms until it's {@code true} or {@code timeout} elapses; never sleeps past either. */
    private static boolean awaitTrue(Duration timeout, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    /**
     * Intercepts {@link SseEmitter#send(SseEventBuilder)} instead of going through a real servlet
     * response, capturing exactly the JSON {@code String} {@link LiveUpdateRegistry#broadcast}
     * wrote for this connection (filtered to the {@code data:} payload itself — {@link
     * SseEventBuilder#build()} also carries the raw {@code "id:...\n"} protocol text as its own,
     * separate {@code TEXT_PLAIN} entry, which this double is not interested in). {@link
     * #send(SseEventBuilder)} adds a random 0-3ms delay so several connections dispatched
     * concurrently actually race on the way to {@link #received}, giving {@link
     * #concurrentDispatchNeverReordersOneConnectionsOwnEnvelopes} something real to fail on if
     * {@code LiveConnection}'s own per-connection write-ordering chain regressed.
     */
    private static final class RecordingSseEmitter extends SseEmitter {
        private final List<String> received = new CopyOnWriteArrayList<>();

        @Override
        public void send(SseEventBuilder builder) {
            try {
                Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextInt(0, 4));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (DataWithMediaType entry : builder.build()) {
                if (MediaType.APPLICATION_JSON.equals(entry.getMediaType()) && entry.getData() instanceof String text) {
                    received.add(text);
                }
            }
        }

        List<String> received() {
            return received;
        }
    }

    /**
     * Simulates a stalled/dead client: {@link #send(SseEventBuilder)} blocks on a caller-supplied
     * {@link CountDownLatch} instead of ever completing, standing in for a real {@code
     * SseEmitter.send} stuck on a slow/dead TCP write. A 30s ceiling on the {@code await} itself
     * (not the latch this class's tests actually release explicitly once they're done asserting)
     * exists purely so a bug in a test can never hang the whole suite.
     */
    private static final class BlockingSseEmitter extends SseEmitter {
        private final CountDownLatch releaseLatch;

        BlockingSseEmitter(CountDownLatch releaseLatch) {
            this.releaseLatch = releaseLatch;
        }

        @Override
        public void send(SseEventBuilder builder) {
            try {
                releaseLatch.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ImmediateScheduledExecutorService moved to its own top-level file in this package (shared with
    // LiveUpdateStatusProviderTest) -- see that class's javadoc.
}

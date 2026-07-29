package com.drones.vision.application;

import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.AssetUsage;
import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.GeoPosition;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.Telemetry;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.AssetRepositoryPort;
import com.drones.vision.domain.port.out.AssetUsageRepositoryPort;
import com.drones.vision.domain.port.out.DeviceRepositoryPort;
import com.drones.vision.domain.port.out.LiveUpdatePublisherPort;
import com.drones.vision.domain.port.out.TelemetryRepositoryPort;
import com.drones.vision.domain.port.out.TelemetrySourcePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UsageTrackerTest {

    private static final CategoryId DRONE = new CategoryId("drone");

    private AssetRepositoryPort assetRepository;
    private DeviceRepositoryPort deviceRepository;
    private AssetUsageRepositoryPort usageRepository;
    private TelemetryRepositoryPort telemetryRepository;
    private Ownership ownership;

    @BeforeEach
    void setUp() {
        assetRepository = mock(AssetRepositoryPort.class);
        deviceRepository = mock(DeviceRepositoryPort.class);
        usageRepository = mock(AssetUsageRepositoryPort.class);
        telemetryRepository = mock(TelemetryRepositoryPort.class);
        ownership = new Ownership(UserId.random(), GroupId.random());

        when(usageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private UsageTracker tracker(List<TelemetrySourcePort> sources) {
        return new UsageTracker(assetRepository, deviceRepository, usageRepository, telemetryRepository, sources);
    }

    private UsageTracker tracker(List<TelemetrySourcePort> sources, LiveUpdatePublisherPort liveUpdatePublisherPort) {
        return new UsageTracker(assetRepository, deviceRepository, usageRepository, telemetryRepository, sources,
                liveUpdatePublisherPort);
    }

    /** docs/OPS-CORE-PLAN.md §G: same as {@link #tracker}, plus a {@link GeofenceMonitor} collaborator. */
    private UsageTracker tracker(List<TelemetrySourcePort> sources, GeofenceMonitor geofenceMonitor) {
        return new UsageTracker(assetRepository, deviceRepository, usageRepository, telemetryRepository, sources,
                null, geofenceMonitor);
    }

    /**
     * docs/MVP2-PLAN.md §S, S-a: same as {@link #tracker}, but with a tiny (20ms) source reopen
     * backoff instead of production's real 1s-30s one, via the package-private test-seam
     * constructor -- so supervision tests complete quickly and deterministically.
     */
    private UsageTracker trackerWithFastRetry(List<TelemetrySourcePort> sources) {
        return new UsageTracker(assetRepository, deviceRepository, usageRepository, telemetryRepository, sources,
                null, null, TimeUnit.MILLISECONDS.toNanos(20), TimeUnit.MILLISECONDS.toNanos(20));
    }

    @Test
    void unownedDeviceIsTrackedAsANoOp() {
        DeviceId deviceId = DeviceId.random();
        when(assetRepository.findByDeviceId(deviceId)).thenReturn(Optional.empty());
        UsageTracker tracker = tracker(List.of());

        tracker.onStreamStarted(deviceId, StreamId.random());
        tracker.onStreamStopped(deviceId);

        verify(usageRepository, never()).save(any());
        verify(telemetryRepository, never()).save(any(), any());
    }

    @Test
    void multiDeviceAssetOpensExactlyOneUsageOnFirstActiveDeviceAndClosesOnLast() {
        Device cam = videoDevice("cam-1");
        Device telemetry = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(cam.id(), telemetry.id()));
        when(assetRepository.findByDeviceId(cam.id())).thenReturn(Optional.of(asset));
        when(assetRepository.findByDeviceId(telemetry.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetry.id())).thenReturn(Optional.of(telemetry));
        UsageTracker tracker = tracker(List.of());
        StreamId camStreamId = StreamId.random();

        tracker.onStreamStarted(cam.id(), camStreamId);
        tracker.onStreamStarted(telemetry.id(), StreamId.random());

        ArgumentCaptor<AssetUsage> openCaptor = ArgumentCaptor.forClass(AssetUsage.class);
        verify(usageRepository, times(1)).save(openCaptor.capture());
        AssetUsage opened = openCaptor.getValue();
        assertEquals(asset.id(), opened.assetId());
        assertNull(opened.endedAt());
        assertEquals(camStreamId, opened.streamId(),
                "the usage must be stamped with the FIRST device's streamId, not any later one");

        // First stop: the asset still has one active device, usage must stay open.
        tracker.onStreamStopped(cam.id());
        verify(usageRepository, times(1)).save(any());

        // Last stop: usage must close now.
        tracker.onStreamStopped(telemetry.id());
        ArgumentCaptor<AssetUsage> allSaves = ArgumentCaptor.forClass(AssetUsage.class);
        verify(usageRepository, times(2)).save(allSaves.capture());
        AssetUsage closed = allSaves.getAllValues().get(1);
        assertEquals(opened.id(), closed.id());
        assertTrue(closed.endedAt() != null && !closed.endedAt().isBefore(closed.startedAt()));
        assertEquals(camStreamId, closed.streamId(), "closing must preserve the recorded streamId");
    }

    @Test
    void reopeningAfterACloseStartsAFreshUsage() {
        Device cam = videoDevice("cam-1");
        Asset asset = asset(Set.of(cam.id()));
        when(assetRepository.findByDeviceId(cam.id())).thenReturn(Optional.of(asset));
        UsageTracker tracker = tracker(List.of());
        StreamId firstStreamId = StreamId.random();
        StreamId secondStreamId = StreamId.random();

        tracker.onStreamStarted(cam.id(), firstStreamId);
        tracker.onStreamStopped(cam.id());
        tracker.onStreamStarted(cam.id(), secondStreamId);

        ArgumentCaptor<AssetUsage> captor = ArgumentCaptor.forClass(AssetUsage.class);
        verify(usageRepository, times(3)).save(captor.capture());
        AssetUsage firstOpen = captor.getAllValues().get(0);
        AssetUsage secondOpen = captor.getAllValues().get(2);
        assertTrue(firstOpen.endedAt() == null);
        assertTrue(secondOpen.endedAt() == null);
        assertTrue(!firstOpen.id().equals(secondOpen.id()), "a new stop/start cycle must open a new usage");
        assertEquals(firstStreamId, firstOpen.streamId());
        assertEquals(secondStreamId, secondOpen.streamId());
    }

    @Test
    void telemetrySamplesArePersistedAndFoldedIntoTheUsageSummary() throws InterruptedException {
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));

        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        UsageTracker tracker = tracker(List.of(source));

        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());

        Telemetry sample1 = telemetry(telemetryDevice.id(), 50.0, 30.0, 95.0);
        source.emit(telemetryDevice.id(), sample1);

        ArgumentCaptor<AssetUsage> afterFirst = ArgumentCaptor.forClass(AssetUsage.class);
        verify(usageRepository, times(2)).save(afterFirst.capture()); // 1 open + 1 sample update
        AssetUsage usageAfterFirst = afterFirst.getValue();
        assertEquals(new GeoPosition(50.0, 30.0, null), usageAfterFirst.startPosition());
        assertEquals(new GeoPosition(50.0, 30.0, null), usageAfterFirst.lastPosition());
        assertEquals(1, usageAfterFirst.sampleCount());
        verify(telemetryRepository).save(usageAfterFirst.id(), sample1);

        Telemetry sample2 = telemetry(telemetryDevice.id(), 50.001, 30.001, 94.9);
        source.emit(telemetryDevice.id(), sample2);

        ArgumentCaptor<AssetUsage> afterSecond = ArgumentCaptor.forClass(AssetUsage.class);
        verify(usageRepository, times(3)).save(afterSecond.capture());
        AssetUsage usageAfterSecond = afterSecond.getValue();
        assertEquals(new GeoPosition(50.0, 30.0, null), usageAfterSecond.startPosition(),
                "start position must stay pinned to the first sample");
        assertEquals(new GeoPosition(50.001, 30.001, null), usageAfterSecond.lastPosition());
        assertEquals(2, usageAfterSecond.sampleCount());
        verify(telemetryRepository).save(usageAfterSecond.id(), sample2);

        tracker.onStreamStopped(telemetryDevice.id());
        // docs/MVP2-PLAN.md §S, S-a: the actual close() call now runs on a background thread (same
        // fix as DefaultStreamService's own stop() -- see that class's javadoc), so this must be
        // awaited rather than checked synchronously right after onStreamStopped returns.
        assertTrue(source.closeLatch.await(1, TimeUnit.SECONDS), "telemetry must be unsubscribed/closed on usage close");
        assertTrue(source.closedDevices.contains(telemetryDevice.id()));
    }

    @Test
    void announcesEveryAppendedSampleAsALiveUpdateWhenConfigured() {
        // docs/REALTIME-PLAN.md §4: applySample is the single write path for a telemetry sample --
        // the live-update announcement rides along with the persist/summary-fold, attributed to the
        // owning asset (not the device the sample physically came from).
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        LiveUpdatePublisherPort liveUpdatePublisherPort = mock(LiveUpdatePublisherPort.class);
        UsageTracker tracker = tracker(List.of(source), liveUpdatePublisherPort);

        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());
        Telemetry sample = telemetry(telemetryDevice.id(), 50.0, 30.0, 95.0);
        source.emit(telemetryDevice.id(), sample);

        verify(liveUpdatePublisherPort).publishTelemetryAppended(asset.id(), sample);
    }

    @Test
    void appliedSampleInvokesTheConfiguredGeofenceMonitor() {
        // docs/OPS-CORE-PLAN.md §G: applySample is the single write path for a telemetry sample --
        // the geofence evaluation rides along with the persist/live-update steps, attributed to the
        // owning asset.
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        GeofenceMonitor geofenceMonitor = mock(GeofenceMonitor.class);
        UsageTracker tracker = tracker(List.of(source), geofenceMonitor);

        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());
        Telemetry sample = telemetry(telemetryDevice.id(), 50.0, 30.0, 95.0);
        source.emit(telemetryDevice.id(), sample);

        verify(geofenceMonitor).evaluate(asset.id(), sample);
    }

    @Test
    void appliedSampleNeverThrowsWhenNoGeofenceMonitorIsConfigured() {
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        UsageTracker tracker = tracker(List.of(source)); // no GeofenceMonitor configured

        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());
        source.emit(telemetryDevice.id(), telemetry(telemetryDevice.id(), 50.0, 30.0, 95.0));
        // No exception must be thrown -- the nullable-collaborator contract holds even under a real emit.
    }

    @Test
    void telemetrySourceFailureTriggersASupervisedReopenAndTheUsageStaysOpenThroughout() throws InterruptedException {
        // docs/MVP2-PLAN.md §S, S-a: a telemetry source error/completion must never end telemetry
        // for the usage -- it is retried (proven here by a second open() call), and the same usage
        // (never closed/reopened) keeps accumulating samples once the new subscription is flowing.
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));

        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        CountDownLatch reopened = new CountDownLatch(1);
        AtomicInteger openCount = new AtomicInteger();
        source.onOpen = () -> {
            if (openCount.incrementAndGet() == 2) {
                reopened.countDown();
            }
        };
        UsageTracker tracker = trackerWithFastRetry(List.of(source));

        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());
        assertEquals(1, openCount.get());

        source.currentPublisher(telemetryDevice.id()).error(new RuntimeException("radio dropout"));

        assertTrue(reopened.await(2, TimeUnit.SECONDS), "the telemetry source must be reopened after a failure");

        Telemetry sample = telemetry(telemetryDevice.id(), 12.0, 34.0, 80.0);
        source.emit(telemetryDevice.id(), sample);

        ArgumentCaptor<AssetUsage> captor = ArgumentCaptor.forClass(AssetUsage.class);
        verify(usageRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        AssetUsage latest = captor.getValue();
        assertNull(latest.endedAt(), "the usage must still be open -- a source reconnect must never close/reopen it");
        assertEquals(1, latest.sampleCount(), "the sample folded through the reconnected publisher into the SAME usage");
        assertEquals(new GeoPosition(12.0, 34.0, null), latest.lastPosition());
    }

    @Test
    void explicitStopDuringTelemetryBackoffCancelsThePendingRetryAndNoFurtherOpenEverHappens() throws InterruptedException {
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));

        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        AtomicInteger openCount = new AtomicInteger();
        source.onOpen = openCount::incrementAndGet;
        UsageTracker tracker = trackerWithFastRetry(List.of(source));

        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());
        assertEquals(1, openCount.get());

        source.currentPublisher(telemetryDevice.id()).error(new RuntimeException("radio dropout"));
        tracker.onStreamStopped(telemetryDevice.id());

        // Give the (now-cancelled) 20ms backoff window plenty of time to have fired if it hadn't
        // actually been cancelled.
        Thread.sleep(300);
        assertEquals(1, openCount.get(), "no further reopen may happen once the usage has been explicitly closed");
    }

    @Test
    void samplesWithoutPositionStillIncrementSampleCountButLeavePositionsUntouched() {
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));

        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        UsageTracker tracker = tracker(List.of(source));
        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());

        Telemetry batteryOnly = new Telemetry(telemetryDevice.id(), Instant.now(), null, null, null, null, 88.0, Map.of());
        source.emit(telemetryDevice.id(), batteryOnly);

        ArgumentCaptor<AssetUsage> captor = ArgumentCaptor.forClass(AssetUsage.class);
        verify(usageRepository, times(2)).save(captor.capture());
        AssetUsage updated = captor.getValue();
        assertNull(updated.startPosition());
        assertNull(updated.lastPosition());
        assertEquals(1, updated.sampleCount());
    }

    @Test
    void onlyTelemetryCapableDevicesAreSubscribed() {
        Device cam = videoDevice("cam-1");
        Asset asset = asset(Set.of(cam.id()));
        when(assetRepository.findByDeviceId(cam.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(cam.id())).thenReturn(Optional.of(cam));

        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        UsageTracker tracker = tracker(List.of(source));

        tracker.onStreamStarted(cam.id(), StreamId.random());

        assertTrue(source.openedDevices.isEmpty(), "a video-only device must never be subscribed for telemetry");
    }

    @Test
    void resolveAssetReturnsTheOwningAssetId() {
        Device cam = videoDevice("cam-1");
        Asset asset = asset(Set.of(cam.id()));
        when(assetRepository.findByDeviceId(cam.id())).thenReturn(Optional.of(asset));
        UsageTracker tracker = tracker(List.of());

        assertEquals(Optional.of(asset.id()), tracker.resolveAsset(cam.id()));
    }

    @Test
    void resolveAssetIsEmptyForAnUnownedDevice() {
        DeviceId deviceId = DeviceId.random();
        when(assetRepository.findByDeviceId(deviceId)).thenReturn(Optional.empty());
        UsageTracker tracker = tracker(List.of());

        assertEquals(Optional.empty(), tracker.resolveAsset(deviceId));
    }

    @Test
    void latestPositionIsEmptyBeforeAnyUsageHasEverOpened() {
        UsageTracker tracker = tracker(List.of());

        assertEquals(Optional.empty(), tracker.latestPosition(AssetId.random()));
    }

    @Test
    void latestPositionIsEmptyWhileTheOpenUsageHasNoPositionedSampleYet() {
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        UsageTracker tracker = tracker(List.of(new ScriptedTelemetrySource(d -> true)));

        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());

        assertEquals(Optional.empty(), tracker.latestPosition(asset.id()));
    }

    @Test
    void latestPositionReflectsTheFreshestTelemetrySampleOnTheOpenUsage() {
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        UsageTracker tracker = tracker(List.of(source));
        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());

        source.emit(telemetryDevice.id(), telemetry(telemetryDevice.id(), 50.0, 30.0, 95.0));
        assertEquals(Optional.of(new GeoPosition(50.0, 30.0, null)), tracker.latestPosition(asset.id()));

        source.emit(telemetryDevice.id(), telemetry(telemetryDevice.id(), 50.001, 30.001, 94.9));
        assertEquals(Optional.of(new GeoPosition(50.001, 30.001, null)), tracker.latestPosition(asset.id()));
    }

    @Test
    void latestPositionIsEmptyOnceTheUsageHasClosed() {
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        UsageTracker tracker = tracker(List.of(source));
        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());
        source.emit(telemetryDevice.id(), telemetry(telemetryDevice.id(), 50.0, 30.0, 95.0));

        tracker.onStreamStopped(telemetryDevice.id());

        assertEquals(Optional.empty(), tracker.latestPosition(asset.id()),
                "no currently open usage means no honest 'freshest' position to report");
    }

    @Test
    void latestTelemetryIsEmptyBeforeAnyUsageHasEverOpened() {
        UsageTracker tracker = tracker(List.of());

        assertEquals(Optional.empty(), tracker.latestTelemetry(AssetId.random()));
    }

    @Test
    void latestTelemetryReflectsTheFreshestSample() {
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        UsageTracker tracker = tracker(List.of(source));
        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());

        Telemetry sample1 = telemetry(telemetryDevice.id(), 50.0, 30.0, 95.0);
        source.emit(telemetryDevice.id(), sample1);
        assertEquals(Optional.of(sample1), tracker.latestTelemetry(asset.id()));

        Telemetry sample2 = telemetry(telemetryDevice.id(), 50.001, 30.001, 94.9);
        source.emit(telemetryDevice.id(), sample2);
        assertEquals(Optional.of(sample2), tracker.latestTelemetry(asset.id()));
    }

    @Test
    void latestTelemetryStaysAnsweredAfterTheUsageCloses() {
        // docs/MVP3-PLAN.md C-a: unlike latestPosition (scoped to the currently open usage),
        // latestTelemetry is deliberately still answered once streaming stops -- staleness is most
        // useful exactly once an asset has gone quiet.
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        UsageTracker tracker = tracker(List.of(source));
        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());
        Telemetry sample = telemetry(telemetryDevice.id(), 50.0, 30.0, 95.0);
        source.emit(telemetryDevice.id(), sample);

        tracker.onStreamStopped(telemetryDevice.id());

        assertEquals(Optional.of(sample), tracker.latestTelemetry(asset.id()),
                "the last sample must still be reported once the usage has closed");
    }

    private static Telemetry telemetry(DeviceId deviceId, double lat, double lon, double battery) {
        return new Telemetry(deviceId, Instant.now(), lat, lon, null, 0.0, battery, Map.of());
    }

    private static Device videoDevice(String name) {
        return new Device(DeviceId.random(), name, Set.of(Capability.VIDEO),
                new StreamDescriptor("sim", URI.create("sim://" + name), Map.of()));
    }

    private static Device telemetryDevice(String name) {
        return new Device(DeviceId.random(), name, Set.of(Capability.TELEMETRY),
                new StreamDescriptor("sim", URI.create("sim://" + name), Map.of()));
    }

    private Asset asset(Set<DeviceId> devices) {
        return new Asset(AssetId.random(), "my drone", DRONE, ownership, devices, Map.of());
    }

    /**
     * Test double for {@link TelemetrySourcePort} that lets the test push
     * samples on demand via {@link #emit} rather than waiting on a real
     * scheduler, so telemetry-sampling assertions are deterministic and fast.
     */
    private static final class ScriptedTelemetrySource implements TelemetrySourcePort {
        private final java.util.function.Predicate<Device> supportsPredicate;
        private final Map<DeviceId, ScriptedPublisher> publishers = new ConcurrentHashMap<>();
        final List<DeviceId> openedDevices = new CopyOnWriteArrayList<>();
        final List<DeviceId> closedDevices = new CopyOnWriteArrayList<>();
        /** Counts down on every {@link #close}; docs/MVP2-PLAN.md §S, S-a moved the real close() call onto a background thread, so tests await this instead of checking synchronously. */
        final CountDownLatch closeLatch = new CountDownLatch(1);
        /** Test hook invoked at the end of every {@link #open}, e.g. to count/signal a supervised reopen (docs/MVP2-PLAN.md §S, S-a). */
        volatile Runnable onOpen;

        ScriptedTelemetrySource(java.util.function.Predicate<Device> supportsPredicate) {
            this.supportsPredicate = supportsPredicate;
        }

        @Override
        public boolean supports(Device device) {
            return supportsPredicate.test(device);
        }

        @Override
        public Flow.Publisher<Telemetry> open(Device device) {
            openedDevices.add(device.id());
            ScriptedPublisher publisher = new ScriptedPublisher();
            // Fired from ScriptedPublisher.subscribe(), not from here: SupervisedPublisher calls
            // open() and then subscribe() on its result as two separate steps, so signalling
            // "reopened" from open() alone would race a test's very next emit() against
            // subscribe() not having run yet.
            publisher.onSubscribed = onOpen;
            publishers.put(device.id(), publisher);
            return publisher;
        }

        @Override
        public void close(DeviceId id) {
            closedDevices.add(id);
            closeLatch.countDown();
        }

        void emit(DeviceId deviceId, Telemetry sample) {
            ScriptedPublisher publisher = publishers.get(deviceId);
            if (publisher != null) {
                publisher.emit(sample);
            }
        }

        /** The publisher returned by the most recent {@link #open} call for {@code deviceId} (docs/MVP2-PLAN.md §S, S-a: lets a test fail the *current* open, whichever attempt it is). */
        ScriptedPublisher currentPublisher(DeviceId deviceId) {
            return publishers.get(deviceId);
        }
    }

    private static final class ScriptedPublisher implements Flow.Publisher<Telemetry> {
        private volatile Flow.Subscriber<? super Telemetry> subscriber;
        private volatile boolean cancelled;
        /** Test hook, set by {@code ScriptedTelemetrySource#open}: fires once {@link #subscriber} is guaranteed non-null. */
        volatile Runnable onSubscribed;

        @Override
        public void subscribe(Flow.Subscriber<? super Telemetry> subscriber) {
            this.subscriber = subscriber;
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    // Samples are delivered synchronously via emit(); nothing to do here.
                }

                @Override
                public void cancel() {
                    cancelled = true;
                }
            });
            Runnable hook = onSubscribed;
            if (hook != null) {
                hook.run();
            }
        }

        void emit(Telemetry sample) {
            Flow.Subscriber<? super Telemetry> s = subscriber;
            if (s != null && !cancelled) {
                s.onNext(sample);
            }
        }

        /** docs/MVP2-PLAN.md §S, S-a: simulates this open's telemetry source failing/disconnecting. */
        void error(Throwable t) {
            Flow.Subscriber<? super Telemetry> s = subscriber;
            if (s != null) {
                s.onError(t);
            }
        }
    }
}

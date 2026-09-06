package com.drones.vision.perception.application.pipeline;

import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.Identity;
import com.drones.vision.warehouse.domain.model.InventoryState;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.warehouse.domain.model.AssetUsage;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.flight.domain.model.FlightPhaseRule;
import com.drones.vision.kernel.FlightState;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.warehouse.domain.model.MaintenanceId;
import com.drones.vision.warehouse.domain.model.MaintenanceKind;
import com.drones.vision.warehouse.domain.model.MaintenanceRecord;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.warehouse.domain.model.UsagePhase;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.kernel.UsageOrigin;
import com.drones.vision.kernel.UserId;
import com.drones.vision.kernel.LifecycleState;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort;
import com.drones.vision.warehouse.domain.port.DeviceRepositoryPort;
import com.drones.vision.flight.domain.port.TelemetryLiveUpdatePort;
import com.drones.vision.flight.domain.port.TelemetryRepositoryPort;
import com.drones.vision.flight.domain.port.TelemetrySourcePort;
import com.drones.vision.warehouse.application.directory.AssetDirectoryService;
import com.drones.vision.warehouse.application.directory.DefaultAssetDirectoryService;
import com.drones.vision.warehouse.application.maintenance.MaintenanceQuery;
import com.drones.vision.warehouse.application.usage.UsageSessionService;
import com.drones.vision.warehouse.application.usage.DefaultUsageSessionService;
import com.drones.vision.flight.application.alerting.LinkLossNotifier;
import com.drones.vision.flight.application.telemetry.TelemetryService;
import com.drones.vision.flight.application.telemetry.DefaultTelemetryService;
import com.drones.vision.platform.Event;
import com.drones.vision.platform.EventPublisherPort;
import com.drones.vision.platform.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.drones.vision.flight.application.geofence.GeofenceMonitor;
import com.drones.vision.perception.application.stream.DefaultStreamService;

class UsageTrackerTest {

    private static final CategoryId DRONE = new CategoryId("drone");

    private AssetRepositoryPort assetRepository;
    private DeviceRepositoryPort deviceRepository;
    private AssetUsageRepositoryPort usageRepository;
    private TelemetryRepositoryPort telemetryRepository;
    private Ownership ownership;

    // docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D1/R3: UsageTracker no longer holds the
    // four repository ports above directly -- it holds these three application services instead.
    // Each is the REAL Default* implementation wrapping the very same mocked repository ports this
    // test already stubs/verifies against, so every existing `verify(usageRepository, times(n))`
    // assertion keeps proving the same thing it always did (the service is a direct pass-through),
    // while UsageTracker itself now only ever sees the service interfaces.
    private AssetDirectoryService assetDirectoryService;
    private UsageSessionService usageSessionService;
    private TelemetryService telemetryService;
    private FakeMaintenanceQuery maintenanceQuery;
    /** docs/plans/active/ASSET-FLOWS-PLAN.md S4/BK2b: real {@link LinkLossNotifier} over a mocked
     *  {@link EventPublisherPort}, so tests can assert on the actual {@code Event} it publishes
     *  rather than on a mocked notifier's method call. */
    private EventPublisherPort linkLossEventPublisher;
    private LinkLossNotifier linkLossNotifier;

    @BeforeEach
    void setUp() {
        assetRepository = mock(AssetRepositoryPort.class);
        deviceRepository = mock(DeviceRepositoryPort.class);
        usageRepository = mock(AssetUsageRepositoryPort.class);
        telemetryRepository = mock(TelemetryRepositoryPort.class);
        ownership = new Ownership(UserId.random(), GroupId.random());

        when(usageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assetDirectoryService = new DefaultAssetDirectoryService(assetRepository, deviceRepository);
        usageSessionService = new DefaultUsageSessionService(usageRepository);
        telemetryService = new DefaultTelemetryService(telemetryRepository);
        maintenanceQuery = new FakeMaintenanceQuery();
        linkLossEventPublisher = mock(EventPublisherPort.class);
        linkLossNotifier = new LinkLossNotifier(linkLossEventPublisher, null);
    }

    private UsageTracker tracker(List<TelemetrySourcePort> sources) {
        return new UsageTracker(assetDirectoryService, usageSessionService, telemetryService, sources,
                UsageTrackerSettings.defaults(maintenanceQuery, linkLossNotifier));
    }

    private UsageTracker tracker(List<TelemetrySourcePort> sources, TelemetryLiveUpdatePort liveUpdatePublisherPort) {
        UsageTrackerSettings defaults = UsageTrackerSettings.defaults(maintenanceQuery, linkLossNotifier);
        return new UsageTracker(assetDirectoryService, usageSessionService, telemetryService, sources,
                new UsageTrackerSettings(Optional.of(liveUpdatePublisherPort), defaults.telemetryObserver(),
                        defaults.sourceInitialBackoffNanos(), defaults.sourceMaxBackoffNanos(),
                        defaults.summaryBatchSettings(), defaults.phaseSettings(), defaults.usagePhaseObserver(),
                        defaults.maintenanceQuery(), defaults.linkLossNotifier()));
    }

    /**
     * docs/plans/done/OPS-CORE-PLAN.md §G: same as {@link #tracker}, plus the telemetry observer
     * seam — bound to {@link GeofenceMonitor#evaluate} exactly as {@code vision-app} wires it in
     * production, so these tests still exercise the real geofence collaboration even though the
     * tracker no longer names that type (docs/plans/active/DOMAIN-SEPARATION-W1.md §5, C2).
     */
    private UsageTracker tracker(List<TelemetrySourcePort> sources, GeofenceMonitor geofenceMonitor) {
        UsageTrackerSettings defaults = UsageTrackerSettings.defaults(maintenanceQuery, linkLossNotifier);
        return new UsageTracker(assetDirectoryService, usageSessionService, telemetryService, sources,
                new UsageTrackerSettings(defaults.liveUpdatePublisherPort(), Optional.of(geofenceMonitor::evaluate),
                        defaults.sourceInitialBackoffNanos(), defaults.sourceMaxBackoffNanos(),
                        defaults.summaryBatchSettings(), defaults.phaseSettings(), defaults.usagePhaseObserver(),
                        defaults.maintenanceQuery(), defaults.linkLossNotifier()));
    }

    /**
     * docs/plans/done/MVP2-PLAN.md §S, S-a: same as {@link #tracker}, but with a tiny (20ms) source reopen
     * backoff instead of production's real 1s-30s one -- so supervision tests complete quickly and
     * deterministically.
     */
    private UsageTracker trackerWithFastRetry(List<TelemetrySourcePort> sources) {
        UsageTrackerSettings defaults = UsageTrackerSettings.defaults(maintenanceQuery, linkLossNotifier);
        return new UsageTracker(assetDirectoryService, usageSessionService, telemetryService, sources,
                new UsageTrackerSettings(defaults.liveUpdatePublisherPort(), defaults.telemetryObserver(),
                        TimeUnit.MILLISECONDS.toNanos(20), TimeUnit.MILLISECONDS.toNanos(20),
                        defaults.summaryBatchSettings(), defaults.phaseSettings(), defaults.usagePhaseObserver(),
                        defaults.maintenanceQuery(), defaults.linkLossNotifier()));
    }

    /**
     * docs/plans/done/SCALE-100-PLAN.md S4: same as {@link #tracker}, but with explicit {@link
     * UsageSummaryBatchSettings}, so coalescing tests can use a tiny batch window instead of waiting
     * out production's default.
     */
    private UsageTracker trackerWithSummaryBatching(List<TelemetrySourcePort> sources,
                                                     UsageSummaryBatchSettings summaryBatchSettings) {
        UsageTrackerSettings defaults = UsageTrackerSettings.defaults(maintenanceQuery, linkLossNotifier);
        return new UsageTracker(assetDirectoryService, usageSessionService, telemetryService, sources,
                new UsageTrackerSettings(defaults.liveUpdatePublisherPort(), defaults.telemetryObserver(),
                        defaults.sourceInitialBackoffNanos(), defaults.sourceMaxBackoffNanos(), summaryBatchSettings,
                        defaults.phaseSettings(), defaults.usagePhaseObserver(), defaults.maintenanceQuery(),
                        defaults.linkLossNotifier()));
    }

    /**
     * docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.3, Wave O7: same as {@link #tracker}, but with
     * explicit {@link UsagePhaseSettings}, so phase-transition tests can use a fixed/steppable clock
     * and short silence/abandon windows instead of waiting out production's real ones.
     */
    private UsageTracker trackerWithPhaseSettings(List<TelemetrySourcePort> sources,
                                                   UsagePhaseSettings phaseSettings) {
        UsageTrackerSettings defaults = UsageTrackerSettings.defaults(maintenanceQuery, linkLossNotifier);
        return new UsageTracker(assetDirectoryService, usageSessionService, telemetryService, sources,
                new UsageTrackerSettings(defaults.liveUpdatePublisherPort(), defaults.telemetryObserver(),
                        defaults.sourceInitialBackoffNanos(), defaults.sourceMaxBackoffNanos(),
                        UsageSummaryBatchSettings.immediate(), phaseSettings, defaults.usagePhaseObserver(),
                        defaults.maintenanceQuery(), defaults.linkLossNotifier()));
    }

    /**
     * The plan's own default windows (docs/plans/active/DRONE-ONBOARDING-PLAN.md §8.1: silence 10s,
     * abandon 120s), driven by a caller-controlled clock rather than a real one -- so a test can
     * step "past" either window instantly instead of sleeping through it.
     */
    /**
     * docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.4, Wave O11: same as {@link
     * #trackerWithPhaseSettings}, plus an explicit {@link UsagePhaseObserver} -- lets the
     * phase-observer firing tests below reuse the same fixed/steppable-clock plumbing as the
     * phase-transition tests above.
     */
    private UsageTracker trackerWithPhaseObserver(List<TelemetrySourcePort> sources, UsagePhaseSettings phaseSettings,
                                                   UsagePhaseObserver usagePhaseObserver) {
        UsageTrackerSettings defaults = UsageTrackerSettings.defaults(maintenanceQuery, linkLossNotifier);
        return new UsageTracker(assetDirectoryService, usageSessionService, telemetryService, sources,
                new UsageTrackerSettings(defaults.liveUpdatePublisherPort(), defaults.telemetryObserver(),
                        defaults.sourceInitialBackoffNanos(), defaults.sourceMaxBackoffNanos(),
                        UsageSummaryBatchSettings.immediate(), phaseSettings, usagePhaseObserver,
                        defaults.maintenanceQuery(), defaults.linkLossNotifier()));
    }

    /**
     * docs/plans/active/ASSET-FLOWS-PLAN.md S1's hand-fake {@link MaintenanceQuery}: empty (nothing
     * grounded) unless a test calls {@link #addRecord}.
     */
    private static final class FakeMaintenanceQuery implements MaintenanceQuery {
        private final Map<AssetId, List<MaintenanceRecord>> recordsByAsset = new HashMap<>();

        void addRecord(AssetId assetId, MaintenanceRecord record) {
            recordsByAsset.computeIfAbsent(assetId, id -> new ArrayList<>()).add(record);
        }

        @Override
        public List<MaintenanceRecord> openBlockers(AssetId assetId) {
            return List.copyOf(recordsByAsset.getOrDefault(assetId, List.of()));
        }
    }

    private static UsagePhaseSettings phaseSettings(AtomicReference<Instant> clock) {
        return new UsagePhaseSettings(clock::get, new FlightPhaseRule(Duration.ofSeconds(10), Duration.ofSeconds(120)));
    }

    private static Telemetry armedSample(DeviceId deviceId, Instant at, Boolean armed) {
        FlightState state = new FlightState(null, null, armed, null, null, null, null, null, List.of());
        return new Telemetry(deviceId, at, null, null, null, null, null, Map.of(), state);
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
        assertNull(opened.pilotId(),
                "a device-pushed stream carries no acting-user context (docs/plans/active/"
                        + "ASSET-FLOWS-PLAN.md §2, D1p) -- an operator later engaging backfills it");

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
        // docs/plans/done/MVP2-PLAN.md §S, S-a: the actual close() call now runs on a background thread (same
        // fix as DefaultStreamService's own stop() -- see that class's javadoc), so this must be
        // awaited rather than checked synchronously right after onStreamStopped returns.
        assertTrue(source.closeLatch.await(1, TimeUnit.SECONDS), "telemetry must be unsubscribed/closed on usage close");
        assertTrue(source.closedDevices.contains(telemetryDevice.id()));
    }

    @Test
    void coalescedSummaryWriteDefersUntilTheSizeBoundThenFlushesTogether() {
        // docs/plans/done/SCALE-100-PLAN.md S4 item 3: the durable telemetryRepository.save call
        // still happens per-sample (asserted below); only the usageRepository summary write is
        // coalesced, onto a huge window so only the size bound can trip it here.
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));

        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        UsageTracker tracker = trackerWithSummaryBatching(List.of(source), new UsageSummaryBatchSettings(3, 60_000));
        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());
        verify(usageRepository, times(1)).save(any()); // the open

        Telemetry sample1 = telemetry(telemetryDevice.id(), 50.0, 30.0, 95.0);
        source.emit(telemetryDevice.id(), sample1);
        verify(telemetryRepository).save(any(), org.mockito.ArgumentMatchers.eq(sample1));
        verify(usageRepository, times(1)).save(any());
        Telemetry sample2 = telemetry(telemetryDevice.id(), 50.001, 30.001, 94.9);
        source.emit(telemetryDevice.id(), sample2);
        verify(usageRepository, times(1)).save(any());

        Telemetry sample3 = telemetry(telemetryDevice.id(), 50.002, 30.002, 94.8);
        source.emit(telemetryDevice.id(), sample3);

        ArgumentCaptor<AssetUsage> captor = ArgumentCaptor.forClass(AssetUsage.class);
        verify(usageRepository, times(2)).save(captor.capture());
        assertEquals(3, captor.getValue().sampleCount(),
                "the third fold trips the size bound and the coalesced write carries all three");
    }

    @Test
    void coalescedSummaryWriteIsDurableWithinTheConfiguredWindowEvenBelowTheSizeBound() throws InterruptedException {
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));

        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        long windowMillis = 60;
        UsageTracker tracker =
                trackerWithSummaryBatching(List.of(source), new UsageSummaryBatchSettings(1000, windowMillis));
        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());
        verify(usageRepository, times(1)).save(any()); // the open

        Telemetry sample = telemetry(telemetryDevice.id(), 50.0, 30.0, 95.0);
        source.emit(telemetryDevice.id(), sample);
        verify(usageRepository, times(1)).save(any());
        Thread.sleep(windowMillis * 3);

        ArgumentCaptor<AssetUsage> captor = ArgumentCaptor.forClass(AssetUsage.class);
        verify(usageRepository, times(2)).save(captor.capture());
        assertEquals(1, captor.getValue().sampleCount(),
                "the batch window bounds how long the summary can lag -- it must flush on its own");
    }

    @Test
    void announcesEveryAppendedSampleAsALiveUpdateWhenConfigured() {
        // docs/plans/done/REALTIME-PLAN.md §4: applySample is the single write path for a telemetry sample --
        // the live-update announcement rides along with the persist/summary-fold, attributed to the
        // owning asset (not the device the sample physically came from).
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        TelemetryLiveUpdatePort liveUpdatePublisherPort = mock(TelemetryLiveUpdatePort.class);
        UsageTracker tracker = tracker(List.of(source), liveUpdatePublisherPort);

        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());
        Telemetry sample = telemetry(telemetryDevice.id(), 50.0, 30.0, 95.0);
        source.emit(telemetryDevice.id(), sample);

        verify(liveUpdatePublisherPort).publishTelemetryAppended(asset.id(), sample);
    }

    @Test
    void appliedSampleInvokesTheConfiguredGeofenceMonitor() {
        // docs/plans/done/OPS-CORE-PLAN.md §G: applySample is the single write path for a telemetry sample --
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
        // docs/plans/done/MVP2-PLAN.md §S, S-a: a telemetry source error/completion must never end telemetry
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
    void telemetrySourceFailureRaisesExactlyOneLinkLostEventNotOnePerRetry() throws InterruptedException {
        // docs/plans/active/ASSET-FLOWS-PLAN.md S4/BK2b: LinkLossNotifier.reportLinkLost must fire on
        // the *outage* edge, never once per retry attempt -- proven here by making the very first
        // retry ALSO fail (still no LINK_LOST for that second failure) before a later retry finally
        // succeeds. This is entirely SupervisedPublisher's own "outageAnnounced" latch (see its
        // javadoc): the onOutageBegan callback this test wires straight to reportLinkLost is already
        // the edge signal, so LinkLossNotifier itself stays deliberately stateless -- no separate
        // hysteresis logic like BatteryMonitor's, because unlike a raw telemetry sample, this
        // callback's own cadence is already edge-only by construction.
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));

        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        AtomicInteger openCount = new AtomicInteger();
        CountDownLatch thirdOpen = new CountDownLatch(1);
        source.onOpen = () -> {
            int n = openCount.incrementAndGet();
            if (n == 2) {
                // The first retry itself fails immediately -- the outage is still ongoing, so this
                // must NOT raise a second LINK_LOST event.
                source.currentPublisher(telemetryDevice.id()).error(new RuntimeException("still down"));
            } else if (n == 3) {
                thirdOpen.countDown();
            }
        };
        UsageTracker tracker = trackerWithFastRetry(List.of(source));

        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());
        assertEquals(1, openCount.get());

        source.currentPublisher(telemetryDevice.id()).error(new RuntimeException("radio dropout"));

        assertTrue(thirdOpen.await(2, TimeUnit.SECONDS),
                "the source must be reopened twice -- one retry that also failed, then one that succeeded");
        assertEquals(3, openCount.get());

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(linkLossEventPublisher, times(1)).publish(captor.capture());
        Event event = captor.getValue();
        assertEquals(EventType.LINK_LOST, event.type());
        assertEquals(asset.id().value().toString(), event.attributes().get("assetId"));
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
        // docs/plans/done/MVP3-PLAN.md C-a: unlike latestPosition (scoped to the currently open usage),
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

    // --- Phase (docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.3, Wave O7) --------------------

    @Test
    void newlyOpenedUsageStartsPreflight() {
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        UsageTracker tracker = tracker(List.of());

        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());

        ArgumentCaptor<AssetUsage> captor = ArgumentCaptor.forClass(AssetUsage.class);
        verify(usageRepository, times(1)).save(captor.capture());
        assertEquals(UsagePhase.PREFLIGHT, captor.getValue().phase());
    }

    @Test
    void armedThenDisarmedWalksPreflightThroughInFlightToPostflight() {
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        UsageTracker tracker = tracker(List.of(source));
        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());

        source.emit(telemetryDevice.id(), armedSample(telemetryDevice.id(), Instant.now(), true));
        ArgumentCaptor<AssetUsage> afterArmed = ArgumentCaptor.forClass(AssetUsage.class);
        verify(usageRepository, times(2)).save(afterArmed.capture());
        assertEquals(UsagePhase.IN_FLIGHT, afterArmed.getValue().phase());

        source.emit(telemetryDevice.id(), armedSample(telemetryDevice.id(), Instant.now(), false));
        ArgumentCaptor<AssetUsage> afterDisarmed = ArgumentCaptor.forClass(AssetUsage.class);
        verify(usageRepository, times(3)).save(afterDisarmed.capture());
        assertEquals(UsagePhase.POSTFLIGHT, afterDisarmed.getValue().phase());
    }

    @Test
    void unknownArmedStateNeverLeavesPreflight() {
        // C7 (docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.3): armed == null is unknown, never a
        // stand-in for "not flying" -- a sample that still can't say must leave the phase exactly
        // where it was.
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        UsageTracker tracker = tracker(List.of(source));
        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());

        source.emit(telemetryDevice.id(), armedSample(telemetryDevice.id(), Instant.now(), null));

        ArgumentCaptor<AssetUsage> captor = ArgumentCaptor.forClass(AssetUsage.class);
        verify(usageRepository, times(2)).save(captor.capture());
        assertEquals(UsagePhase.PREFLIGHT, captor.getValue().phase());
    }

    @Test
    void silenceThenReheardWalksInFlightThroughLinkLostBackToInFlight() {
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        AtomicReference<Instant> clock = new AtomicReference<>(base);
        UsageTracker tracker = trackerWithPhaseSettings(List.of(source), phaseSettings(clock));
        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());
        source.emit(telemetryDevice.id(), armedSample(telemetryDevice.id(), base, true)); // -> IN_FLIGHT
        verify(usageRepository, times(2)).save(any());

        clock.set(base.plusSeconds(15)); // past the 10s silence window, no new sample arrives
        tracker.evaluateLinkHealth(asset.id());

        ArgumentCaptor<AssetUsage> afterSilence = ArgumentCaptor.forClass(AssetUsage.class);
        verify(usageRepository, times(3)).save(afterSilence.capture());
        assertEquals(UsagePhase.LINK_LOST, afterSilence.getValue().phase());

        // Re-heard, still armed, well within the 120s abandon window.
        source.emit(telemetryDevice.id(), armedSample(telemetryDevice.id(), base.plusSeconds(16), true));

        ArgumentCaptor<AssetUsage> afterReheard = ArgumentCaptor.forClass(AssetUsage.class);
        verify(usageRepository, times(4)).save(afterReheard.capture());
        assertEquals(UsagePhase.IN_FLIGHT, afterReheard.getValue().phase());
    }

    @Test
    void sessionClosedWhileStillArmedBecomesAbandoned() {
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        UsageTracker tracker = tracker(List.of(source));
        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());
        source.emit(telemetryDevice.id(), armedSample(telemetryDevice.id(), Instant.now(), true)); // -> IN_FLIGHT

        tracker.onStreamStopped(telemetryDevice.id());

        ArgumentCaptor<AssetUsage> captor = ArgumentCaptor.forClass(AssetUsage.class);
        verify(usageRepository, times(3)).save(captor.capture());
        assertEquals(UsagePhase.ABANDONED, captor.getValue().phase());
        assertTrue(captor.getValue().endedAt() != null, "an abandoned session is still a closed one");
    }

    // --- UsagePhaseObserver (docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.4, Wave O11) --------

    @Test
    void phaseObserverFiresOnceOnUsageOpenWithNullPrevious() {
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        List<Captured> observed = new CopyOnWriteArrayList<>();
        UsageTracker tracker = trackerWithPhaseObserver(List.of(), UsagePhaseSettings.defaults(),
                (assetId, usageId, previous, next, at) -> observed.add(new Captured(assetId, usageId, previous, next)));

        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());

        assertEquals(1, observed.size(), "opening a usage must notify the observer exactly once");
        Captured opened = observed.get(0);
        assertEquals(asset.id(), opened.assetId());
        assertNull(opened.previous(), "nothing transitions into the initial phase -- previous must be null");
        assertEquals(UsagePhase.PREFLIGHT, opened.next());
    }

    @Test
    void phaseObserverFiresWithPreviousAndNextOnARealTransition() {
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        List<Captured> observed = new CopyOnWriteArrayList<>();
        UsageTracker tracker = trackerWithPhaseObserver(List.of(source), UsagePhaseSettings.defaults(),
                (assetId, usageId, previous, next, at) -> observed.add(new Captured(assetId, usageId, previous, next)));
        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());
        observed.clear(); // drop the open notification -- only the transition below is under test

        source.emit(telemetryDevice.id(), armedSample(telemetryDevice.id(), Instant.now(), true)); // -> IN_FLIGHT

        assertEquals(1, observed.size());
        Captured transition = observed.get(0);
        assertEquals(UsagePhase.PREFLIGHT, transition.previous());
        assertEquals(UsagePhase.IN_FLIGHT, transition.next());
    }

    @Test
    void phaseObserverDoesNotFireWhenThePhaseIsUnchanged() {
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        List<Captured> observed = new CopyOnWriteArrayList<>();
        UsageTracker tracker = trackerWithPhaseObserver(List.of(source), UsagePhaseSettings.defaults(),
                (assetId, usageId, previous, next, at) -> observed.add(new Captured(assetId, usageId, previous, next)));
        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());
        observed.clear();

        // armed == null is unknown, never a stand-in for "not flying" (see
        // unknownArmedStateNeverLeavesPreflight above) -- the phase stays PREFLIGHT, so the observer
        // must not fire a second time.
        source.emit(telemetryDevice.id(), armedSample(telemetryDevice.id(), Instant.now(), null));

        assertTrue(observed.isEmpty(), "an unchanged phase must not notify the observer");
    }

    @Test
    void aThrowingPhaseObserverNeverBreaksSampling() {
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        UsagePhaseObserver throwingObserver = (assetId, usageId, previous, next, at) -> {
            throw new RuntimeException("boom");
        };
        UsageTracker tracker = trackerWithPhaseObserver(List.of(source), UsagePhaseSettings.defaults(), throwingObserver);

        // The open call itself fires the observer (previous == null) -- opening the usage must
        // still succeed even though the observer throws.
        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());

        Telemetry sample = telemetry(telemetryDevice.id(), 50.0, 30.0, 95.0);
        source.emit(telemetryDevice.id(), sample);

        ArgumentCaptor<AssetUsage> captor = ArgumentCaptor.forClass(AssetUsage.class);
        verify(usageRepository, times(2)).save(captor.capture()); // 1 open + 1 sample update, neither broken
        assertEquals(1, captor.getValue().sampleCount());
    }

    private record Captured(AssetId assetId, UsageId usageId, UsagePhase previous, UsagePhase next) {
    }

    // --- engage/disengage (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D2, wave R2) -----

    @Test
    void engageOpensTelemetryForTheAssetsTelemetryCapableDevicesWithNoVideoStreamInvolved() {
        // docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §3 P4, closes
        // docs/plans/active/TELEMETRY-ONLY-ONBOARDING-CONTEXT.md §B4: #engage now carries its own
        // telemetry traffic -- unlike the deleted onTelemetryDeviceDiscovered it replaces, no video
        // stream is ever involved, but a telemetry subscription genuinely opens (this used to assert
        // the opposite -- B4 is exactly the defect that made that assertion wrong).
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        UsageTracker tracker = tracker(List.of(source));

        AssetUsage opened = tracker.engage(asset.id(), null);

        assertEquals(asset.id(), opened.assetId());
        assertNull(opened.streamId(), "an operator-engaged usage has no video stream to stamp");
        assertEquals(UsageOrigin.OPERATOR, opened.origin());
        assertEquals(UsagePhase.PREFLIGHT, opened.phase());
        assertNull(opened.endedAt());
        verify(usageRepository, times(1)).save(opened);
        assertEquals(List.of(telemetryDevice.id()), source.openedDevices,
                "engage must open telemetry for the asset's telemetry-capable device -- no video stream required");
    }

    @Test
    void engageIsIdempotentForAnAlreadyEngagedAsset() {
        Asset asset = asset(Set.of(telemetryDevice("tel-1").id()));
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        UsageTracker tracker = tracker(List.of());

        AssetUsage first = tracker.engage(asset.id(), null);
        AssetUsage second = tracker.engage(asset.id(), null);

        assertEquals(first.id(), second.id());
        assertEquals(UsageOrigin.OPERATOR, second.origin());
        verify(usageRepository, times(1)).save(any()); // the second call is a pure no-op, no re-save
    }

    // -- pilot attribution (docs/plans/active/ASSET-FLOWS-PLAN.md §2, D1p) ----------------------

    @Test
    void engageWithAKnownPilotStampsTheOpenedUsage() {
        Asset asset = asset(Set.of(telemetryDevice("tel-1").id()));
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        UsageTracker tracker = tracker(List.of());
        UserId pilotId = UserId.random();

        AssetUsage opened = tracker.engage(asset.id(), pilotId);

        assertEquals(pilotId, opened.pilotId());
    }

    @Test
    void engageWithNoKnownPilotLeavesPilotIdHonestlyNull() {
        Asset asset = asset(Set.of(telemetryDevice("tel-1").id()));
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        UsageTracker tracker = tracker(List.of());

        AssetUsage opened = tracker.engage(asset.id(), null);

        assertNull(opened.pilotId());
    }

    @Test
    void engagingAnAlreadyOperatorEngagedUsageNeverOverwritesTheRecordedPilot() {
        // "First attribution wins" (AssetUsage#pilotId() javadoc): idempotent re-engage, even by a
        // different pilot, must not disturb who was recorded first.
        Asset asset = asset(Set.of(telemetryDevice("tel-1").id()));
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        UsageTracker tracker = tracker(List.of());
        UserId firstPilot = UserId.random();
        UserId secondPilot = UserId.random();

        AssetUsage first = tracker.engage(asset.id(), firstPilot);
        AssetUsage second = tracker.engage(asset.id(), secondPilot);

        assertEquals(firstPilot, first.pilotId());
        assertEquals(firstPilot, second.pilotId(), "re-engaging must never overwrite the first-recorded pilot");
    }

    @Test
    void promotingAStreamOpenedUsageBackfillsTheEngagingPilot() {
        // A device-pushed stream carries no acting-user context (see #deviceStreamStarted), so the
        // usage it opens has pilotId == null until an operator explicitly engages it.
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        UsageTracker tracker = tracker(List.of(new ScriptedTelemetrySource(d -> true)));
        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());
        UserId pilotId = UserId.random();

        AssetUsage promoted = tracker.engage(asset.id(), pilotId);

        assertEquals(pilotId, promoted.pilotId(), "promoting a stream-opened usage must backfill the engaging pilot");
    }

    @Test
    void promotingAStreamOpenedUsageWithNoPilotKnownLeavesPilotIdNull() {
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        UsageTracker tracker = tracker(List.of(new ScriptedTelemetrySource(d -> true)));
        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());

        AssetUsage promoted = tracker.engage(asset.id(), null);

        assertNull(promoted.pilotId());
    }

    @Test
    void engageOnAnUnknownAssetThrowsNotFound() {
        AssetId unknown = AssetId.random();
        when(assetRepository.findById(unknown)).thenReturn(Optional.empty());
        UsageTracker tracker = tracker(List.of());

        org.junit.jupiter.api.Assertions.assertThrows(java.util.NoSuchElementException.class,
                () -> tracker.engage(unknown, null));
        verify(usageRepository, never()).save(any());
    }

    @Test
    void engageOnADeactivatedAssetThrowsIllegalState() {
        DeviceId deviceId = telemetryDevice("tel-1").id();
        Instant now = Instant.now();
        Asset asset = new Asset(AssetId.random(), "my drone", DRONE, ownership, Set.of(deviceId), Map.of(),
                LifecycleState.DEACTIVATED, Identity.NONE, Custody.NONE, InventoryState.IN_STOCK, now, now);
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        UsageTracker tracker = tracker(List.of());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> tracker.engage(asset.id(), null));
        verify(usageRepository, never()).save(any());
    }

    // --- ASSET-FLOWS-PLAN S1: engage refuses on a maintenance-grounded asset -------------------

    @Test
    void engageRefusesWhenAssetIsMaintenanceGroundedWithoutOpeningAnyUsage() {
        Asset asset = asset(Set.of(telemetryDevice("tel-1").id()));
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        maintenanceQuery.addRecord(asset.id(), new MaintenanceRecord(MaintenanceId.random(), asset.id(),
                MaintenanceKind.GROUNDING, Instant.EPOCH, null, UserId.random(), "Propeller crack found", null));
        UsageTracker tracker = tracker(List.of());

        IllegalStateException ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> tracker.engage(asset.id(), null));

        assertTrue(ex.getMessage().contains(asset.id().value().toString()), "got: " + ex.getMessage());
        verify(usageRepository, never()).save(any());
    }

    @Test
    void engageProceedsWhenAnOpenMaintenanceRecordDoesNotBlockFlight() {
        // REPAIR/NOTE are informational (MaintenanceKind#blocksFlight() == false) -- only
        // GROUNDING/INSPECTION_DUE gate engage.
        Asset asset = asset(Set.of(telemetryDevice("tel-1").id()));
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        maintenanceQuery.addRecord(asset.id(), new MaintenanceRecord(MaintenanceId.random(), asset.id(),
                MaintenanceKind.REPAIR, Instant.EPOCH, null, UserId.random(), "Swapping a prop", null));
        UsageTracker tracker = tracker(List.of());

        AssetUsage opened = tracker.engage(asset.id(), null);

        assertEquals(UsageOrigin.OPERATOR, opened.origin());
    }

    @Test
    void onStreamStartedStillOpensAUsageForAMaintenanceGroundedAsset() {
        // Deliberate scope decision (docs/plans/active/ASSET-FLOWS-PLAN.md S1, wave BK1): only the
        // explicit operator verb #engage is gated. The stream-triggered session-open path
        // (#onStreamStarted / #deviceStreamStarted) is NOT gated here, because by the time it fires
        // the video pipeline has already started and the STREAM_STARTED event already published
        // (DefaultStreamService#startStream calls pipeline.start() and publishes the event before
        // ever calling usageTracker#onStreamStarted, and its exception handler never calls
        // pipeline.close()) -- refusing at this point would leak a running pipeline rather than
        // prevent anything, so a grounded asset's stream is still allowed to open a usage exactly as
        // before this wave.
        Device cam = videoDevice("cam-1");
        Asset asset = asset(Set.of(cam.id()));
        when(assetRepository.findByDeviceId(cam.id())).thenReturn(Optional.of(asset));
        maintenanceQuery.addRecord(asset.id(), new MaintenanceRecord(MaintenanceId.random(), asset.id(),
                MaintenanceKind.GROUNDING, Instant.EPOCH, null, UserId.random(), "Propeller crack found", null));
        UsageTracker tracker = tracker(List.of());

        tracker.onStreamStarted(cam.id(), StreamId.random());

        verify(usageRepository, times(1)).save(any());
    }

    @Test
    void disengageClosesAPurelyOperatorEngagedUsageWhenNoDeviceIsActive() {
        Asset asset = asset(Set.of(telemetryDevice("tel-1").id()));
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        UsageTracker tracker = tracker(List.of());
        AssetUsage opened = tracker.engage(asset.id(), null);

        Optional<AssetUsage> result = tracker.disengage(asset.id());

        assertTrue(result.isPresent());
        assertEquals(opened.id(), result.get().id());
        assertTrue(result.get().endedAt() != null, "no device was ever active -- disengage must close outright");
        assertEquals(UsagePhase.CLOSED, result.get().phase());
        ArgumentCaptor<AssetUsage> captor = ArgumentCaptor.forClass(AssetUsage.class);
        verify(usageRepository, times(2)).save(captor.capture()); // 1 open (engage) + 1 close (disengage)
    }

    @Test
    void disengageIsANoOpWhenNothingIsEngagedAtAll() {
        Asset asset = asset(Set.of(telemetryDevice("tel-1").id()));
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        UsageTracker tracker = tracker(List.of());

        Optional<AssetUsage> result = tracker.disengage(asset.id());

        assertEquals(Optional.empty(), result);
        verify(usageRepository, never()).save(any());
    }

    @Test
    void disengageIsANoOpOnAStreamOnlyUsageTheOperatorNeverTouched() {
        Device cam = videoDevice("cam-1");
        Asset asset = asset(Set.of(cam.id()));
        when(assetRepository.findByDeviceId(cam.id())).thenReturn(Optional.of(asset));
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        UsageTracker tracker = tracker(List.of());
        tracker.onStreamStarted(cam.id(), StreamId.random());

        Optional<AssetUsage> result = tracker.disengage(asset.id());

        assertEquals(Optional.empty(), result, "an ordinary STREAM-origin usage is not disengage's concern");
        // the STREAM-origin usage must still be open and untouched -- only the one open() save so far
        verify(usageRepository, times(1)).save(any());
    }

    @Test
    void disengageOnAnUnknownAssetThrowsNotFound() {
        AssetId unknown = AssetId.random();
        when(assetRepository.findById(unknown)).thenReturn(Optional.empty());
        UsageTracker tracker = tracker(List.of());

        org.junit.jupiter.api.Assertions.assertThrows(java.util.NoSuchElementException.class,
                () -> tracker.disengage(unknown));
    }

    // -- the three named collision rules (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D2/R2) --

    @Test
    void collisionRule1_streamStoppingNeverClosesAnOperatorEngagedUsage() {
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        UsageTracker tracker = tracker(List.of(source));

        AssetUsage engaged = tracker.engage(asset.id(), null); // opens the OPERATOR-origin usage first
        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random()); // a stream starts on top of it
        verify(usageRepository, times(1)).save(any()); // starting the stream must NOT open a second usage

        tracker.onStreamStopped(telemetryDevice.id()); // ...and stopping it must not close the first one

        verify(usageRepository, times(1)).save(any()); // still just the one save -- no close() write happened
        // The only way to observe "still open, still OPERATOR" without a repository read is through a
        // second disengage(), which must now find and close exactly the usage engage() opened:
        Optional<AssetUsage> disengaged = tracker.disengage(asset.id());
        assertTrue(disengaged.isPresent());
        assertEquals(engaged.id(), disengaged.get().id());
        assertTrue(disengaged.get().endedAt() != null);
    }

    @Test
    void collisionRule2_engagingAnAlreadyStreamingAssetPromotesRatherThanOpensASecondUsage() {
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        UsageTracker tracker = tracker(List.of(new ScriptedTelemetrySource(d -> true)));
        StreamId streamId = StreamId.random();
        tracker.onStreamStarted(telemetryDevice.id(), streamId); // opens a STREAM-origin usage first

        AssetUsage promoted = tracker.engage(asset.id(), null);

        verify(usageRepository, times(2)).save(any()); // 1 open (stream) + 1 promote (engage) -- never a 2nd open
        assertEquals(UsageOrigin.OPERATOR, promoted.origin());
        assertEquals(streamId, promoted.streamId(), "promotion must not touch the stream that genuinely opened it");

        // Now stopping the stream must not close the promoted usage (this is also collision rule 1):
        tracker.onStreamStopped(telemetryDevice.id());
        verify(usageRepository, times(2)).save(any());
    }

    @Test
    void collisionRule3_disengagingWhileAStreamIsStillRunningDemotesInsteadOfClosing() {
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        UsageTracker tracker = tracker(List.of(new ScriptedTelemetrySource(d -> true)));
        AssetUsage engaged = tracker.engage(asset.id(), null);
        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random()); // device is still active

        Optional<AssetUsage> result = tracker.disengage(asset.id());

        assertTrue(result.isPresent());
        assertEquals(engaged.id(), result.get().id());
        assertNull(result.get().endedAt(), "a still-running stream means demote, not close");
        assertEquals(UsageOrigin.STREAM, result.get().origin());

        // Nothing must be left that will never close: the ordinary stream-driven close now applies.
        tracker.onStreamStopped(telemetryDevice.id());
        ArgumentCaptor<AssetUsage> captor = ArgumentCaptor.forClass(AssetUsage.class);
        verify(usageRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        AssetUsage last = captor.getValue();
        assertEquals(engaged.id(), last.id());
        assertTrue(last.endedAt() != null, "the demoted usage must still be closeable by the ordinary stream stop");
    }

    // -- engage-opened telemetry (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §3 P4, closes
    // docs/plans/active/TELEMETRY-ONLY-ONBOARDING-CONTEXT.md §B4) -- the collision matrix ------

    @Test
    void engageThenStreamStartDoesNotDoubleSubscribeTelemetry() {
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        UsageTracker tracker = tracker(List.of(source));

        tracker.engage(asset.id(), null);
        assertEquals(List.of(telemetryDevice.id()), source.openedDevices,
                "engage on a telemetry-only asset must open telemetry for its device");

        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());

        assertEquals(1, source.openedDevices.size(),
                "a stream starting on a device engage already subscribed must never double-open it");
    }

    @Test
    void streamStartThenEngagePromotesWithoutDoubleSubscribingTelemetry() {
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        UsageTracker tracker = tracker(List.of(source));

        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());
        assertEquals(1, source.openedDevices.size(), "the stream start must open telemetry for the device");

        AssetUsage promoted = tracker.engage(asset.id(), null);

        assertEquals(UsageOrigin.OPERATOR, promoted.origin());
        assertEquals(1, source.openedDevices.size(),
                "engage on an already-streaming device must promote, not double-subscribe telemetry");
    }

    @Test
    void engageThenDisengageWhileStreamRunsLeavesTelemetrySubscriptionOpen() {
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        UsageTracker tracker = tracker(List.of(source));
        tracker.engage(asset.id(), null);
        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random()); // device still active afterward

        Optional<AssetUsage> result = tracker.disengage(asset.id());

        assertTrue(result.isPresent());
        assertNull(result.get().endedAt(), "a still-running stream means demote, not close");
        assertEquals(UsageOrigin.STREAM, result.get().origin());
        assertTrue(source.closedDevices.isEmpty(),
                "the still-running stream's telemetry subscription must survive disengage");
    }

    @Test
    void engageThenDisengageWithNoStreamClosesTelemetrySubscriptionAndTheUsage() throws InterruptedException {
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        UsageTracker tracker = tracker(List.of(source));
        tracker.engage(asset.id(), null);
        assertEquals(1, source.openedDevices.size());

        Optional<AssetUsage> result = tracker.disengage(asset.id());

        assertTrue(result.isPresent());
        assertTrue(result.get().endedAt() != null, "no device was ever active -- disengage must close outright");
        assertEquals(UsagePhase.CLOSED, result.get().phase());
        assertTrue(source.closeLatch.await(1, TimeUnit.SECONDS),
                "the telemetry subscription engage opened must be unsubscribed/closed on disengage");
        assertTrue(source.closedDevices.contains(telemetryDevice.id()));
    }

    @Test
    void engageThenStreamStartThenStreamStopKeepsTelemetryAliveForTheStillEngagedUsage() {
        // The gap this closes: before this wave, deviceStreamStopped tore telemetry down unconditionally
        // once the last device stopped, even for an OPERATOR-origin usage -- silently undoing what
        // #engage opened the moment a paired video stream (if any) happened to stop.
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        UsageTracker tracker = tracker(List.of(source));
        AssetUsage engaged = tracker.engage(asset.id(), null);
        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());

        tracker.onStreamStopped(telemetryDevice.id());

        assertTrue(source.closedDevices.isEmpty(),
                "the engaged usage's telemetry subscription must survive the last stream stopping");
        // The usage itself must also still be open (unchanged pre-existing rule) -- proven the same
        // way collisionRule1 does, since there is no repository read: disengage must still find it.
        Optional<AssetUsage> disengaged = tracker.disengage(asset.id());
        assertTrue(disengaged.isPresent());
        assertEquals(engaged.id(), disengaged.get().id());
        assertTrue(disengaged.get().endedAt() != null);
    }

    @Test
    void engageOpenedTelemetryKeepsLatestTelemetryFreshForIdleCloseActivityTracking() {
        // docs/plans/done/OPERATOR-UX-5-PLAN.md finding U1 / this module's MODULE.md Gotchas: the
        // idle-close sweep (vision-warehouse, DefaultUsageIdleCloseService) reads "last activity" via
        // AssetLiveStatePort#latestTelemetry, which StreamBackedAssetLiveState delegates straight to
        // UsageTracker#latestTelemetry. Before this wave, an engage-only (no stream) usage never had
        // any traffic to report there, so it would eventually look idle even while the aircraft kept
        // transmitting. With #engage opening telemetry itself, a sample it receives must keep
        // latestTelemetry current exactly like a stream-driven one always has.
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        UsageTracker tracker = tracker(List.of(source));
        tracker.engage(asset.id(), null);

        Telemetry sample = telemetry(telemetryDevice.id(), 50.0, 30.0, 95.0);
        source.emit(telemetryDevice.id(), sample);

        assertEquals(Optional.of(sample), tracker.latestTelemetry(asset.id()),
                "the idle-close sweep's activity signal must reflect engage-opened telemetry traffic too");
    }

    // -- ALWAYS-ON-FLOW wave A: telemetry is a fact about the world, not a side effect of video --

    @Test
    void pinnedTelemetrySurvivesTheLastStreamStopping() {
        // The reported defect: an operator pressing Stop -- or, far more often, IdleStreamReaper
        // stopping a stream nobody has watched for ten minutes -- used to end telemetry too.
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        UsageTracker tracker = tracker(List.of(source));

        tracker.pinTelemetry(asset.id());
        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());
        tracker.onStreamStopped(telemetryDevice.id());

        assertTrue(source.closedDevices.isEmpty(),
                "a pinned asset's telemetry source must outlive its last video stream");
    }

    @Test
    void pinIsIdempotentAndOpensTheSourceExactlyOnce() {
        // A reconciler calls this on every tick, so re-pinning must never open a second source.
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        UsageTracker tracker = tracker(List.of(source));

        tracker.pinTelemetry(asset.id());
        tracker.pinTelemetry(asset.id());
        tracker.pinTelemetry(asset.id());

        assertEquals(List.of(telemetryDevice.id()), source.openedDevices);
    }

    @Test
    void pinningOpensTelemetryWithoutOpeningAUsage() {
        // A link is not a flight. Pinning must never fabricate a session -- #engage stays the
        // explicit verb for that.
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        UsageTracker tracker = tracker(List.of(source));

        tracker.pinTelemetry(asset.id());

        assertEquals(List.of(telemetryDevice.id()), source.openedDevices);
        verify(usageRepository, never()).save(any());
    }

    @Test
    void aSampleOnAPinnedButUnengagedAssetPublishesLiveAndEvaluatesGeofenceButRecordsNothing() {
        // ALWAYS-ON-FLOW A2, the doctrine split: with no usage open there is nowhere to record a
        // per-usage row, but the sample is still true -- a breach is a breach whether or not
        // anybody opened a flight, and a watching client must still see the freshest position.
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        GeofenceMonitor geofenceMonitor = mock(GeofenceMonitor.class);
        UsageTracker tracker = tracker(List.of(source), geofenceMonitor);

        tracker.pinTelemetry(asset.id());
        Telemetry sample = telemetry(telemetryDevice.id(), 50.0, 30.0, 95.0);
        source.emit(telemetryDevice.id(), sample);

        verify(geofenceMonitor).evaluate(asset.id(), sample);
        verify(telemetryRepository, never()).save(any(), any());
        verify(usageRepository, never()).save(any());
        assertEquals(Optional.of(sample), tracker.latestTelemetry(asset.id()),
                "latestTelemetry must answer for a live link even with no usage open");
    }

    @Test
    void unpinningReleasesTelemetryOnlyWhenNothingElseNeedsIt() {
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findByDeviceId(telemetryDevice.id())).thenReturn(Optional.of(asset));
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        UsageTracker tracker = tracker(List.of(source));

        tracker.pinTelemetry(asset.id());
        tracker.onStreamStarted(telemetryDevice.id(), StreamId.random());

        tracker.unpinTelemetry(asset.id()); // a device is still active -- must not tear down
        assertTrue(source.closedDevices.isEmpty(),
                "unpinning must not close telemetry an active device still needs");
    }

    @Test
    void unpinningAnIdleAssetReleasesItsTelemetry() throws InterruptedException {
        Device telemetryDevice = telemetryDevice("tel-1");
        Asset asset = asset(Set.of(telemetryDevice.id()));
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        when(deviceRepository.findById(telemetryDevice.id())).thenReturn(Optional.of(telemetryDevice));
        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        UsageTracker tracker = tracker(List.of(source));

        tracker.pinTelemetry(asset.id());
        tracker.unpinTelemetry(asset.id());

        // close() is deferred to a virtual thread (MVP2 §S, S-a), so await the latch.
        assertTrue(source.closeLatch.await(2, TimeUnit.SECONDS), "telemetry source must be closed");
        assertEquals(List.of(telemetryDevice.id()), source.closedDevices);
    }

    @Test
    void pinningAnUnknownAssetIsIgnored() {
        // A reconciler racing a deletion is ordinary, not exceptional.
        ScriptedTelemetrySource source = new ScriptedTelemetrySource(d -> true);
        UsageTracker tracker = tracker(List.of(source));

        tracker.pinTelemetry(AssetId.random());
        tracker.unpinTelemetry(AssetId.random());

        assertTrue(source.openedDevices.isEmpty());
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
        return Asset.register(AssetId.random(), "my drone", DRONE, ownership, devices, Map.of(), Identity.NONE,
                Custody.NONE);
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
        /** Counts down on every {@link #close}; docs/plans/done/MVP2-PLAN.md §S, S-a moved the real close() call onto a background thread, so tests await this instead of checking synchronously. */
        final CountDownLatch closeLatch = new CountDownLatch(1);
        /** Test hook invoked at the end of every {@link #open}, e.g. to count/signal a supervised reopen (docs/plans/done/MVP2-PLAN.md §S, S-a). */
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

        /** The publisher returned by the most recent {@link #open} call for {@code deviceId} (docs/plans/done/MVP2-PLAN.md §S, S-a: lets a test fail the *current* open, whichever attempt it is). */
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

        /** docs/plans/done/MVP2-PLAN.md §S, S-a: simulates this open's telemetry source failing/disconnecting. */
        void error(Throwable t) {
            Flow.Subscriber<? super Telemetry> s = subscriber;
            if (s != null) {
                s.onError(t);
            }
        }
    }
}

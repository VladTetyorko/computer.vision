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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;

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
    void telemetrySamplesArePersistedAndFoldedIntoTheUsageSummary() {
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
        assertTrue(source.closedDevices.contains(telemetryDevice.id()), "telemetry must be unsubscribed/closed on usage close");
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
            publishers.put(device.id(), publisher);
            return publisher;
        }

        @Override
        public void close(DeviceId id) {
            closedDevices.add(id);
        }

        void emit(DeviceId deviceId, Telemetry sample) {
            ScriptedPublisher publisher = publishers.get(deviceId);
            if (publisher != null) {
                publisher.emit(sample);
            }
        }
    }

    private static final class ScriptedPublisher implements Flow.Publisher<Telemetry> {
        private volatile Flow.Subscriber<? super Telemetry> subscriber;
        private volatile boolean cancelled;

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
        }

        void emit(Telemetry sample) {
            Flow.Subscriber<? super Telemetry> s = subscriber;
            if (s != null && !cancelled) {
                s.onNext(sample);
            }
        }
    }
}

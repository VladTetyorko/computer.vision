package com.drones.vision.perception.application.stream;

import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.LifecycleState;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import com.drones.vision.warehouse.application.device.DeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Moved from {@code warehouse.application.asset.DefaultAssetServiceTest} together with the
 * startStream logic itself (docs/plans/active/DOMAIN-SEPARATION-W1.md &sect;15, W1.6e); assertions
 * and scenarios are unchanged, only the collaborator this class resolves the asset through
 * ({@link AssetRepositoryPort} directly, rather than {@code AssetService}).
 */
class DefaultAssetStreamServiceTest {

    private static final CategoryId DRONE = new CategoryId("drone");

    private AssetRepositoryPort assetRepository;
    private DeviceService deviceService;
    private StreamService streamService;
    private Ownership ownership;
    private AssetStreamService service;

    @BeforeEach
    void setUp() {
        assetRepository = mock(AssetRepositoryPort.class);
        deviceService = mock(DeviceService.class);
        streamService = mock(StreamService.class);
        ownership = new Ownership(UserId.random(), GroupId.random());

        service = new DefaultAssetStreamService(assetRepository, deviceService, streamService);
    }

    @Test
    void startStreamResolvesTheAssetsOnlyActiveVideoCapableDeviceWhenNoneIsNamed() {
        Device cam = device("cam-1");
        Device telemetry = telemetryDevice("tel-1");
        Asset stored = asset(Set.of(cam.id(), telemetry.id()));
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));
        when(deviceService.find(cam.id())).thenReturn(Optional.of(cam));
        when(deviceService.find(telemetry.id())).thenReturn(Optional.of(telemetry));
        StreamId expected = StreamId.random();
        when(streamService.start(eq(cam.id()), any(), any())).thenReturn(expected);

        StreamId started = service.startStream(stored.id(), null, PipelineConfig.defaults());

        assertEquals(expected, started);
        verify(streamService).start(cam.id(), PipelineConfig.defaults(), TrackingConfigPatch.NOTHING);
    }

    @Test
    void startStreamRefusesToGuessWhenTheAssetHasSeveralVideoCapableDevices() {
        Device first = device("cam-a");
        Device second = device("cam-b");
        Asset stored = asset(Set.of(first.id(), second.id()));
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));
        when(deviceService.find(first.id())).thenReturn(Optional.of(first));
        when(deviceService.find(second.id())).thenReturn(Optional.of(second));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> service.startStream(stored.id(), null, PipelineConfig.defaults()));

        // The message must name both candidates, so the caller can pick one without a second lookup.
        assertTrue(thrown.getMessage().contains(first.id().value().toString())
                        && thrown.getMessage().contains(second.id().value().toString()),
                "expected message to name both ambiguous candidates: " + thrown.getMessage());
        verify(streamService, never()).start(any(), any());
    }

    @Test
    void startStreamRejectsADeviceThatDoesNotBelongToTheAsset() {
        Device cam = device("cam-1");
        Device stranger = device("someone-elses-cam");
        Asset stored = asset(Set.of(cam.id()));
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));

        assertThrows(IllegalArgumentException.class,
                () -> service.startStream(stored.id(), stranger.id(), PipelineConfig.defaults()));
        verify(streamService, never()).start(any(), any());
    }

    @Test
    void startStreamUsesTheNamedDeviceWhenItBelongsToTheAsset() {
        Device first = device("cam-a");
        Device second = device("cam-b");
        Asset stored = asset(Set.of(first.id(), second.id()));
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));
        StreamId expected = StreamId.random();
        when(streamService.start(eq(second.id()), any(), any())).thenReturn(expected);

        StreamId started = service.startStream(stored.id(), second.id(), PipelineConfig.defaults());

        // Naming a device settles the choice outright: no resolution pass runs, so two
        // video-capable devices are not ambiguous here.
        assertEquals(expected, started);
        verify(deviceService, never()).find(any());
    }

    @Test
    void startStreamRefusesWhenTheAssetIsNotInService() {
        Device cam = device("cam-1");
        Asset stored = asset(Set.of(cam.id())).withState(LifecycleState.DEACTIVATED);
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));

        assertThrows(IllegalStateException.class,
                () -> service.startStream(stored.id(), cam.id(), PipelineConfig.defaults()));
        verify(streamService, never()).start(any(), any());
    }

    @Test
    void startStreamSkipsDeactivatedAndDeletedDevicesWhenResolving() {
        Device working = device("cam-working");
        Device retired = device("cam-retired").withState(LifecycleState.DEACTIVATED);
        Device removed = device("cam-removed").withState(LifecycleState.DELETED);
        Asset stored = asset(Set.of(working.id(), retired.id(), removed.id()));
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));
        when(deviceService.find(working.id())).thenReturn(Optional.of(working));
        when(deviceService.find(retired.id())).thenReturn(Optional.of(retired));
        when(deviceService.find(removed.id())).thenReturn(Optional.of(removed));

        service.startStream(stored.id(), null, PipelineConfig.defaults());

        // Out-of-service sources are invisible to resolution, so one working camera among
        // three video-capable ones is not ambiguity.
        verify(streamService).start(working.id(), PipelineConfig.defaults(), TrackingConfigPatch.NOTHING);
    }

    @Test
    void startStreamRefusesWhenNoDeviceOfTheAssetCanProduceVideo() {
        Device telemetry = telemetryDevice("tel-1");
        Asset stored = asset(Set.of(telemetry.id()));
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));
        when(deviceService.find(telemetry.id())).thenReturn(Optional.of(telemetry));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> service.startStream(stored.id(), null, PipelineConfig.defaults()));

        assertTrue(thrown.getMessage().contains("no active video-capable device"));
        verify(streamService, never()).start(any(), any());
    }

    @Test
    void startStreamThrowsForUnknownAsset() {
        AssetId unknown = AssetId.random();
        when(assetRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> service.startStream(unknown, null, PipelineConfig.defaults()));
    }

    private static Device device(String name) {
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
}

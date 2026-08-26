package com.drones.vision.warehouse.application.device;

import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.kernel.Capability;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.platform.Event;
import com.drones.vision.platform.EventType;
import com.drones.vision.kernel.LifecycleState;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.warehouse.domain.port.AssetLiveStatePort;
import com.drones.vision.warehouse.domain.port.DeviceRepositoryPort;
import com.drones.vision.platform.EventPublisherPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultDeviceServiceTest {

    private static final StreamDescriptor SIM =
            new StreamDescriptor("sim", URI.create("sim://cam"), Map.of());

    private DeviceRepositoryPort deviceRepository;
    private AssetLiveStatePort assetLiveStatePort;
    private AuditTrailPort auditTrail;
    private EventPublisherPort eventPublisher;
    private UserId actingUser;
    private DeviceService service;

    @BeforeEach
    void setUp() {
        deviceRepository = mock(DeviceRepositoryPort.class);
        assetLiveStatePort = mock(AssetLiveStatePort.class);
        auditTrail = mock(AuditTrailPort.class);
        eventPublisher = mock(EventPublisherPort.class);
        actingUser = UserId.random();

        service = new DefaultDeviceService(deviceRepository, assetLiveStatePort, auditTrail, eventPublisher);

        when(deviceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Device stored(String name, LifecycleState state) {
        Device device = new Device(DeviceId.random(), name, Set.of(Capability.VIDEO), SIM, state);
        when(deviceRepository.findById(device.id())).thenReturn(Optional.of(device));
        return device;
    }

    // --- Registering ---------------------------------------------------------

    @Test
    void registerPersistsDeviceWithGeneratedIdAndPublishesDeviceOnline() {
        DeviceRegistration registration =
                new DeviceRegistration("cam-1", Set.of(Capability.VIDEO), SIM);

        Device saved = service.register(registration, actingUser);

        assertNotNull(saved.id());
        assertEquals("cam-1", saved.name());
        assertEquals(LifecycleState.ACTIVE, saved.state());

        ArgumentCaptor<Device> deviceCaptor = ArgumentCaptor.forClass(Device.class);
        verify(deviceRepository).save(deviceCaptor.capture());
        assertSame(saved.id(), deviceCaptor.getValue().id());

        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
        verify(eventPublisher).publish(eventCaptor.capture());
        assertEquals(EventType.DEVICE_ONLINE, eventCaptor.getValue().type());
        assertEquals(null, eventCaptor.getValue().streamId());

        assertEquals(AuditAction.CREATED, recordedAudit().action());
    }

    // --- Listing -------------------------------------------------------------

    @Test
    void devicesHidesSoftDeletedOnesByDefaultButCanIncludeThem() {
        Device live = new Device(DeviceId.random(), "live", Set.of(Capability.VIDEO), SIM, LifecycleState.ACTIVE);
        Device gone = new Device(DeviceId.random(), "gone", Set.of(Capability.VIDEO), SIM, LifecycleState.DELETED);
        when(deviceRepository.findAll()).thenReturn(List.of(live, gone));

        assertEquals(List.of(live), service.devices());
        assertEquals(List.of(live, gone), service.devices(true));
    }

    // --- Editing -------------------------------------------------------------

    @Test
    void updateAppliesOnlyTheFieldsSentAndLeavesTheRestAlone() {
        Device device = stored("old-name", LifecycleState.ACTIVE);

        Device updated = service.update(device.id(),
                new DeviceEdit("new-name", null, null, null), actingUser);

        assertEquals("new-name", updated.name());
        assertEquals(device.capabilities(), updated.capabilities());
        assertEquals(device.stream(), updated.stream());
        assertEquals(device.state(), updated.state());
        assertEquals(device.origin(), updated.origin());
    }

    @Test
    void updateAppliesAReplacementOrigin() {
        Device device = stored("cam", LifecycleState.ACTIVE);

        Device updated = service.update(device.id(),
                new DeviceEdit(null, null, null, com.drones.vision.kernel.DeviceOrigin.SIMULATED), actingUser);

        assertEquals(com.drones.vision.kernel.DeviceOrigin.SIMULATED, updated.origin());
        assertTrue(recordedAudit().details().get("origin").contains("SIMULATED"));
    }

    @Test
    void updateRecordsWhatActuallyChanged() {
        Device device = stored("old-name", LifecycleState.ACTIVE);
        StreamDescriptor moved = new StreamDescriptor("rtsp", URI.create("rtsp://10.0.0.9/s"), Map.of());

        service.update(device.id(), new DeviceEdit("new-name", null, moved, null), actingUser);

        AuditEntry entry = recordedAudit();
        assertEquals(AuditAction.UPDATED, entry.action());
        assertEquals(AuditTargetType.DEVICE, entry.targetType());
        assertTrue(entry.details().get("name").contains("old-name"));
        assertTrue(entry.details().get("stream").contains("rtsp://10.0.0.9/s"));
        // Untouched fields must not appear as changes.
        assertEquals(null, entry.details().get("capabilities"));
    }

    @Test
    void updateThatChangesNothingWritesNoAuditLine() {
        Device device = stored("cam", LifecycleState.ACTIVE);

        service.update(device.id(), DeviceEdit.NOTHING, actingUser);

        verify(auditTrail, never()).record(any());
    }

    @Test
    void updateThrowsForUnknownDevice() {
        DeviceId unknown = DeviceId.random();
        when(deviceRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> service.update(unknown, DeviceEdit.NOTHING, actingUser));
    }

    // --- Deactivating --------------------------------------------------------

    @Test
    void deactivatingStopsTheDeviceStreamSoTheStateIsARuleNotALabel() {
        Device device = stored("cam", LifecycleState.ACTIVE);

        Device result = service.setState(device.id(), LifecycleState.DEACTIVATED, actingUser);

        assertEquals(LifecycleState.DEACTIVATED, result.state());
        // Which stream (if any) actually stops is StreamBackedAssetLiveState's own concern (tested
        // there) -- this service's job is only to ask the port to stop this one device.
        verify(assetLiveStatePort).stopStreamsForDevices(Set.of(device.id()));
        assertEquals(AuditAction.DEACTIVATED, recordedAudit().action());
    }

    @Test
    void settingTheStateItIsAlreadyInChangesNothing() {
        Device device = stored("cam", LifecycleState.ACTIVE);

        Device result = service.setState(device.id(), LifecycleState.ACTIVE, actingUser);

        assertSame(device, result);
        verify(deviceRepository, never()).save(any());
        verify(auditTrail, never()).record(any());
    }

    // --- Deleting (soft) -----------------------------------------------------

    @Test
    void deleteMarksTheDeviceRatherThanRemovingIt() {
        Device device = stored("cam", LifecycleState.ACTIVE);

        Device deleted = service.delete(device.id(), actingUser);

        assertEquals(LifecycleState.DELETED, deleted.state());
        verify(deviceRepository).save(any());
        // The whole point of a soft delete: the row survives, so history stays attributable.
        verify(deviceRepository, never()).deleteById(any());
        assertEquals(AuditAction.DELETED, recordedAudit().action());
    }

    @Test
    void deleteStopsAnyRunningStreamFirst() {
        Device device = stored("cam", LifecycleState.ACTIVE);

        service.delete(device.id(), actingUser);

        verify(assetLiveStatePort).stopStreamsForDevices(Set.of(device.id()));
    }

    @Test
    void deleteIsIdempotent() {
        Device device = stored("cam", LifecycleState.DELETED);

        Device result = service.delete(device.id(), actingUser);

        assertSame(device, result);
        verify(deviceRepository, never()).save(any());
    }

    // --- Restoring -----------------------------------------------------------

    @Test
    void aDeletedDeviceCannotBeActivatedStraightBackOntoTheAir() {
        Device device = stored("cam", LifecycleState.DELETED);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.setState(device.id(), LifecycleState.ACTIVE, actingUser));

        assertTrue(thrown.getMessage().contains("restore"));
        verify(deviceRepository, never()).save(any());
    }

    @Test
    void restoringADeletedDeviceBringsItBackOutOfService() {
        Device device = stored("cam", LifecycleState.DELETED);

        Device restored = service.setState(device.id(), LifecycleState.DEACTIVATED, actingUser);

        assertEquals(LifecycleState.DEACTIVATED, restored.state());
        assertEquals(AuditAction.RESTORED, recordedAudit().action());
    }

    private AuditEntry recordedAudit() {
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditTrail).record(captor.capture());
        AuditEntry entry = captor.getValue();
        assertEquals(actingUser, entry.actor());
        return entry;
    }
}

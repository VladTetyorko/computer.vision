package com.drones.vision.application;

import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.AuditAction;
import com.drones.vision.domain.model.AuditEntry;
import com.drones.vision.domain.model.AuditTargetType;
import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.CommandResult;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.LifecycleState;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.AuditTrailPort;
import com.drones.vision.domain.port.out.FlightCommandPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultFlightCommandServiceTest {

    private static final CategoryId DRONE = new CategoryId("drone");

    private AssetService assetService;
    private FlightCommandPort flightCommandPort;
    private AuditTrailPort auditTrail;
    private FlightCommandService service;

    private final UserId actor = UserId.random();
    private final AssetId assetId = AssetId.random();
    private Device telemetryDevice;

    @BeforeEach
    void setUp() {
        assetService = mock(AssetService.class);
        flightCommandPort = mock(FlightCommandPort.class);
        auditTrail = mock(AuditTrailPort.class);
        service = new DefaultFlightCommandService(assetService, flightCommandPort, auditTrail);

        telemetryDevice = new Device(DeviceId.random(), "FC", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("mavlink", URI.create("udp://127.0.0.1:14550"), java.util.Map.of()));
    }

    private void stubDetails(Device... devices) {
        Asset asset = new Asset(assetId, "Drone 1", DRONE, new Ownership(actor, GroupId.random()),
                Set.of(devices[0].id()), java.util.Map.of());
        AssetSummary summary = new AssetSummary(asset, "Drone", AssetStatus.OFFLINE, null, null);
        when(assetService.details(assetId)).thenReturn(new AssetDetails(summary, List.of(devices), List.of()));
    }

    @Test
    void returnToHomeReturnsAcceptedAndAuditsSuccess() {
        stubDetails(telemetryDevice);
        when(flightCommandPort.supports(telemetryDevice)).thenReturn(true);
        when(flightCommandPort.returnToHome(telemetryDevice)).thenReturn(CommandResult.ACCEPTED);

        CommandResult result = service.returnToHome(assetId, actor);

        assertEquals(CommandResult.ACCEPTED, result);
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditTrail).record(captor.capture());
        AuditEntry entry = captor.getValue();
        assertEquals(AuditAction.UPDATED, entry.action());
        assertEquals(AuditTargetType.ASSET, entry.targetType());
        assertEquals(assetId.value().toString(), entry.targetId());
        assertEquals(assetId.value().toString(), entry.details().get("assetId"));
        assertEquals("RTL", entry.details().get("command"));
        assertEquals("ACCEPTED", entry.details().get("result"));
    }

    @Test
    void returnToHomePassesThroughNoAckAndAuditsIt() {
        stubDetails(telemetryDevice);
        when(flightCommandPort.supports(telemetryDevice)).thenReturn(true);
        when(flightCommandPort.returnToHome(telemetryDevice)).thenReturn(CommandResult.NO_ACK);

        CommandResult result = service.returnToHome(assetId, actor);

        assertEquals(CommandResult.NO_ACK, result);
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditTrail).record(captor.capture());
        assertEquals("NO_ACK", captor.getValue().details().get("result"));
    }

    @Test
    void returnToHomeThrowsNoSuchElementForAnUnknownAsset() {
        when(assetService.details(assetId)).thenThrow(new NoSuchElementException("Unknown asset: " + assetId.value()));

        assertThrows(NoSuchElementException.class, () -> service.returnToHome(assetId, actor));
        verify(auditTrail, never()).record(any());
    }

    @Test
    void returnToHomeThrowsIllegalStateWhenNoDeviceSupportsCommanding() {
        stubDetails(telemetryDevice);
        when(flightCommandPort.supports(telemetryDevice)).thenReturn(false);

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> service.returnToHome(assetId, actor));
        assertTrue(ex.getMessage().contains(assetId.value().toString()));
        verify(flightCommandPort, never()).returnToHome(any());
        verify(auditTrail, never()).record(any());
    }

    @Test
    void returnToHomeIgnoresInactiveDevicesWhenResolvingWhichDeviceToCommand() {
        Device deactivated = telemetryDevice.withState(LifecycleState.DEACTIVATED);
        Device active = new Device(DeviceId.random(), "FC2", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("mavlink", URI.create("udp://127.0.0.1:14551"), java.util.Map.of()));
        Asset asset = new Asset(assetId, "Drone 1", DRONE, new Ownership(actor, GroupId.random()),
                Set.of(deactivated.id(), active.id()), java.util.Map.of());
        AssetSummary summary = new AssetSummary(asset, "Drone", AssetStatus.OFFLINE, null, null);
        when(assetService.details(assetId))
                .thenReturn(new AssetDetails(summary, List.of(deactivated, active), List.of()));
        when(flightCommandPort.supports(any())).thenReturn(true);
        when(flightCommandPort.returnToHome(active)).thenReturn(CommandResult.ACCEPTED);

        CommandResult result = service.returnToHome(assetId, actor);

        assertEquals(CommandResult.ACCEPTED, result);
        verify(flightCommandPort, never()).returnToHome(deactivated);
        verify(flightCommandPort).returnToHome(active);
    }

    @Test
    void returnToHomeTranslatesPortIllegalArgumentExceptionIntoIllegalStateExceptionAndAudits() {
        stubDetails(telemetryDevice);
        when(flightCommandPort.supports(telemetryDevice)).thenReturn(true);
        when(flightCommandPort.returnToHome(telemetryDevice))
                .thenThrow(new IllegalArgumentException("Betaflight has no return-to-home capability"));

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> service.returnToHome(assetId, actor));
        assertEquals("Betaflight has no return-to-home capability", ex.getMessage());

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditTrail).record(captor.capture());
        assertEquals("REFUSED:Betaflight has no return-to-home capability", captor.getValue().details().get("result"));
    }

    @Test
    void returnToHomePropagatesPortIllegalStateExceptionAndAuditsTheRefusal() {
        stubDetails(telemetryDevice);
        when(flightCommandPort.supports(telemetryDevice)).thenReturn(true);
        when(flightCommandPort.returnToHome(telemetryDevice))
                .thenThrow(new IllegalStateException("Vehicle sysid 1 refused return-to-home: MAV_RESULT_DENIED"));

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> service.returnToHome(assetId, actor));
        assertEquals("Vehicle sysid 1 refused return-to-home: MAV_RESULT_DENIED", ex.getMessage());

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditTrail).record(captor.capture());
        assertEquals("REFUSED:Vehicle sysid 1 refused return-to-home: MAV_RESULT_DENIED",
                captor.getValue().details().get("result"));
    }
}

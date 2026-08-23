package com.drones.vision.flight.application;

import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.flight.domain.model.CommandResult;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.flight.domain.model.FlightCapability;
import com.drones.vision.flight.domain.model.VehicleKind;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.LifecycleState;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.flight.domain.port.FlightCommandPort;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.drones.vision.warehouse.application.asset.AssetDetails;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.asset.AssetStatus;
import com.drones.vision.warehouse.application.asset.AssetSummary;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.VisibilityScope;

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

        CommandResult result = service.returnToHome(assetId, actor, VisibilityScope.unbounded());

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

        CommandResult result = service.returnToHome(assetId, actor, VisibilityScope.unbounded());

        assertEquals(CommandResult.NO_ACK, result);
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditTrail).record(captor.capture());
        assertEquals("NO_ACK", captor.getValue().details().get("result"));
    }

    @Test
    void returnToHomeThrowsNoSuchElementForAnUnknownAsset() {
        when(assetService.details(assetId)).thenThrow(new NoSuchElementException("Unknown asset: " + assetId.value()));

        assertThrows(NoSuchElementException.class, () -> service.returnToHome(assetId, actor, VisibilityScope.unbounded()));
        verify(auditTrail, never()).record(any());
    }

    @Test
    void returnToHomeThrowsIllegalStateWhenNoDeviceSupportsCommanding() {
        stubDetails(telemetryDevice);
        when(flightCommandPort.supports(telemetryDevice)).thenReturn(false);

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> service.returnToHome(assetId, actor, VisibilityScope.unbounded()));
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

        CommandResult result = service.returnToHome(assetId, actor, VisibilityScope.unbounded());

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
                assertThrows(IllegalStateException.class, () -> service.returnToHome(assetId, actor, VisibilityScope.unbounded()));
        assertEquals("Betaflight has no return-to-home capability", ex.getMessage());

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditTrail).record(captor.capture());
        assertEquals("REFUSED:Betaflight has no return-to-home capability", captor.getValue().details().get("result"));
    }

    @Test
    void returnToHomeDeniedWhenAssetIsOutOfScopeAndAuditsTheDenial() {
        stubDetails(telemetryDevice);

        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> service.returnToHome(assetId, actor, VisibilityScope.groups(Set.of())));
        assertTrue(ex.getMessage().contains(assetId.value().toString()));

        verify(flightCommandPort, never()).returnToHome(any());
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditTrail).record(captor.capture());
        assertEquals("DENIED:out of scope", captor.getValue().details().get("result"));
    }

    @Test
    void returnToHomeAllowedWhenAssetIsWithinAGroupScope() {
        GroupId group = GroupId.random();
        Asset asset = new Asset(assetId, "Drone 1", DRONE, new Ownership(actor, group),
                Set.of(telemetryDevice.id()), java.util.Map.of());
        AssetSummary summary = new AssetSummary(asset, "Drone", AssetStatus.OFFLINE, null, null);
        when(assetService.details(assetId))
                .thenReturn(new AssetDetails(summary, List.of(telemetryDevice), List.of()));
        when(flightCommandPort.supports(telemetryDevice)).thenReturn(true);
        when(flightCommandPort.returnToHome(telemetryDevice)).thenReturn(CommandResult.ACCEPTED);

        CommandResult result = service.returnToHome(assetId, actor, VisibilityScope.groups(Set.of(group)));

        assertEquals(CommandResult.ACCEPTED, result);
        verify(flightCommandPort).returnToHome(telemetryDevice);
    }

    @Test
    void returnToHomePropagatesPortIllegalStateExceptionAndAuditsTheRefusal() {
        stubDetails(telemetryDevice);
        when(flightCommandPort.supports(telemetryDevice)).thenReturn(true);
        when(flightCommandPort.returnToHome(telemetryDevice))
                .thenThrow(new IllegalStateException("Vehicle sysid 1 refused return-to-home: MAV_RESULT_DENIED"));

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> service.returnToHome(assetId, actor, VisibilityScope.unbounded()));
        assertEquals("Vehicle sysid 1 refused return-to-home: MAV_RESULT_DENIED", ex.getMessage());

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditTrail).record(captor.capture());
        assertEquals("REFUSED:Vehicle sysid 1 refused return-to-home: MAV_RESULT_DENIED",
                captor.getValue().details().get("result"));
    }

    // --- Stage 2: setMode ---

    @Test
    void setModeReturnsAcceptedAndAuditsSuccess() {
        stubDetails(telemetryDevice);
        when(flightCommandPort.supports(telemetryDevice)).thenReturn(true);
        when(flightCommandPort.capabilities(telemetryDevice))
                .thenReturn(new FlightCapability(true, true, true, List.of("Loiter", "RTL"), VehicleKind.COPTER));
        when(flightCommandPort.setMode(telemetryDevice, "Loiter")).thenReturn(CommandResult.ACCEPTED);

        CommandResult result = service.setMode(assetId, "Loiter", actor, VisibilityScope.unbounded());

        assertEquals(CommandResult.ACCEPTED, result);
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditTrail).record(captor.capture());
        assertEquals("MODE:Loiter", captor.getValue().details().get("command"));
        assertEquals("ACCEPTED", captor.getValue().details().get("result"));
    }

    @Test
    void setModePassesThroughNoAck() {
        stubDetails(telemetryDevice);
        when(flightCommandPort.supports(telemetryDevice)).thenReturn(true);
        when(flightCommandPort.capabilities(telemetryDevice))
                .thenReturn(new FlightCapability(true, true, true, List.of("Loiter", "RTL"), VehicleKind.COPTER));
        when(flightCommandPort.setMode(telemetryDevice, "RTL")).thenReturn(CommandResult.NO_ACK);

        assertEquals(CommandResult.NO_ACK, service.setMode(assetId, "RTL", actor, VisibilityScope.unbounded()));
    }

    @Test
    void setModeRejectsAnUnknownModeAsIllegalArgumentAndDoesNotSendOrAudit() {
        stubDetails(telemetryDevice);
        when(flightCommandPort.supports(telemetryDevice)).thenReturn(true);
        when(flightCommandPort.capabilities(telemetryDevice))
                .thenReturn(new FlightCapability(true, true, true, List.of("Loiter", "RTL"), VehicleKind.COPTER));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.setMode(assetId, "Barrel-Roll", actor, VisibilityScope.unbounded()));
        assertTrue(ex.getMessage().contains("Barrel-Roll"));
        verify(flightCommandPort, never()).setMode(any(), any());
        verify(auditTrail, never()).record(any());
    }

    @Test
    void setModeOnANotCommandableVehicleStays409ViaThePortNot400() {
        // A Betaflight-like vehicle: supports() true (mavlink device) but capabilities report
        // modeSelectSupported=false, so the pre-flight mode check is skipped and the port itself
        // rejects the attempt -> IllegalStateException (409), never the unknown-mode 400.
        stubDetails(telemetryDevice);
        when(flightCommandPort.supports(telemetryDevice)).thenReturn(true);
        when(flightCommandPort.capabilities(telemetryDevice)).thenReturn(FlightCapability.notCommandable());
        when(flightCommandPort.setMode(telemetryDevice, "Loiter"))
                .thenThrow(new IllegalArgumentException("Betaflight is not commandable"));

        assertThrows(IllegalStateException.class,
                () -> service.setMode(assetId, "Loiter", actor, VisibilityScope.unbounded()));
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditTrail).record(captor.capture());
        assertEquals("REFUSED:Betaflight is not commandable", captor.getValue().details().get("result"));
    }

    @Test
    void setModeDeniedWhenAssetIsOutOfScopeAndAuditsTheDenial() {
        stubDetails(telemetryDevice);

        assertThrows(AccessDeniedException.class,
                () -> service.setMode(assetId, "Loiter", actor, VisibilityScope.groups(Set.of())));
        verify(flightCommandPort, never()).setMode(any(), any());
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditTrail).record(captor.capture());
        assertEquals("DENIED:out of scope", captor.getValue().details().get("result"));
    }

    // --- Stage 2: arm / disarm ---

    @Test
    void armReturnsAcceptedAndAuditsSuccess() {
        stubDetails(telemetryDevice);
        when(flightCommandPort.supports(telemetryDevice)).thenReturn(true);
        when(flightCommandPort.arm(telemetryDevice, true)).thenReturn(CommandResult.ACCEPTED);

        CommandResult result = service.arm(assetId, true, actor, VisibilityScope.unbounded());

        assertEquals(CommandResult.ACCEPTED, result);
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditTrail).record(captor.capture());
        assertEquals("ARM", captor.getValue().details().get("command"));
        assertEquals("ACCEPTED", captor.getValue().details().get("result"));
    }

    @Test
    void armPassesThroughNoAck() {
        stubDetails(telemetryDevice);
        when(flightCommandPort.supports(telemetryDevice)).thenReturn(true);
        when(flightCommandPort.arm(telemetryDevice, false)).thenReturn(CommandResult.NO_ACK);

        assertEquals(CommandResult.NO_ACK, service.arm(assetId, false, actor, VisibilityScope.unbounded()));
    }

    @Test
    void armTranslatesPortIllegalArgumentIntoIllegalStateAndAudits() {
        stubDetails(telemetryDevice);
        when(flightCommandPort.supports(telemetryDevice)).thenReturn(true);
        when(flightCommandPort.arm(telemetryDevice, false))
                .thenThrow(new IllegalArgumentException("Betaflight cannot be armed via MAVLink"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.arm(assetId, false, actor, VisibilityScope.unbounded()));
        assertEquals("Betaflight cannot be armed via MAVLink", ex.getMessage());
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditTrail).record(captor.capture());
        assertEquals("REFUSED:Betaflight cannot be armed via MAVLink", captor.getValue().details().get("result"));
    }

    @Test
    void armDeniedWhenAssetIsOutOfScopeAndAuditsTheDenial() {
        stubDetails(telemetryDevice);

        assertThrows(AccessDeniedException.class,
                () -> service.arm(assetId, true, actor, VisibilityScope.groups(Set.of())));
        verify(flightCommandPort, never()).arm(any(), anyBoolean());
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditTrail).record(captor.capture());
        assertEquals("DENIED:out of scope", captor.getValue().details().get("result"));
    }

    @Test
    void disarmReturnsAcceptedAndAuditsWithForceFlagPassedThrough() {
        stubDetails(telemetryDevice);
        when(flightCommandPort.supports(telemetryDevice)).thenReturn(true);
        when(flightCommandPort.disarm(telemetryDevice, true)).thenReturn(CommandResult.ACCEPTED);

        CommandResult result = service.disarm(assetId, true, actor, VisibilityScope.unbounded());

        assertEquals(CommandResult.ACCEPTED, result);
        verify(flightCommandPort).disarm(telemetryDevice, true);
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditTrail).record(captor.capture());
        assertEquals("DISARM", captor.getValue().details().get("command"));
    }

    @Test
    void disarmThrowsIllegalStateWhenNoDeviceSupportsCommandingAndDoesNotAudit() {
        stubDetails(telemetryDevice);
        when(flightCommandPort.supports(telemetryDevice)).thenReturn(false);

        assertThrows(IllegalStateException.class,
                () -> service.disarm(assetId, false, actor, VisibilityScope.unbounded()));
        verify(flightCommandPort, never()).disarm(any(), anyBoolean());
        verify(auditTrail, never()).record(any());
    }

    // --- Stage 2: capabilities (scoped read) ---

    @Test
    void capabilitiesReturnsThePortSnapshotForAnInScopeAsset() {
        Asset asset = new Asset(assetId, "Drone 1", DRONE, new Ownership(actor, GroupId.random()),
                Set.of(telemetryDevice.id()), java.util.Map.of());
        AssetSummary summary = new AssetSummary(asset, "Drone", AssetStatus.OFFLINE, null, null);
        when(assetService.details(any(VisibilityScope.class), eq(assetId)))
                .thenReturn(new AssetDetails(summary, List.of(telemetryDevice), List.of()));
        when(flightCommandPort.supports(telemetryDevice)).thenReturn(true);
        FlightCapability caps = new FlightCapability(true, true, true, List.of("Loiter", "RTL"), VehicleKind.COPTER);
        when(flightCommandPort.capabilities(telemetryDevice)).thenReturn(caps);

        assertEquals(caps, service.capabilities(assetId, VisibilityScope.unbounded()));
        verify(auditTrail, never()).record(any());
    }

    @Test
    void capabilitiesThrowsNoSuchElementWhenTheScopedReadHidesTheAsset() {
        when(assetService.details(any(VisibilityScope.class), eq(assetId)))
                .thenThrow(new NoSuchElementException("Unknown asset: " + assetId.value()));

        assertThrows(NoSuchElementException.class,
                () -> service.capabilities(assetId, VisibilityScope.groups(Set.of())));
    }

    @Test
    void capabilitiesReportsNotCommandableForAnAssetWithNoCommandableDevice() {
        Asset asset = new Asset(assetId, "Warehouse cam", DRONE, new Ownership(actor, GroupId.random()),
                Set.of(telemetryDevice.id()), java.util.Map.of());
        AssetSummary summary = new AssetSummary(asset, "Drone", AssetStatus.OFFLINE, null, null);
        when(assetService.details(any(VisibilityScope.class), eq(assetId)))
                .thenReturn(new AssetDetails(summary, List.of(telemetryDevice), List.of()));
        when(flightCommandPort.supports(telemetryDevice)).thenReturn(false);

        FlightCapability caps = service.capabilities(assetId, VisibilityScope.unbounded());
        assertEquals(FlightCapability.notCommandable(), caps);
        verify(flightCommandPort, never()).capabilities(any());
    }
}

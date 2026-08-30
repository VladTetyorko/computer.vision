package com.drones.vision.flight.application;

import com.drones.vision.flight.domain.model.MessageIntervalOutcome;
import com.drones.vision.flight.domain.model.ParameterReading;
import com.drones.vision.flight.domain.model.ParameterWriteOutcome;
import com.drones.vision.flight.domain.model.RemediationResultCode;
import com.drones.vision.flight.domain.port.VehicleConfigPort;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.FlightState;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.application.asset.AssetDetails;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.asset.AssetStatus;
import com.drones.vision.warehouse.application.asset.AssetSummary;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.warehouse.domain.model.Identity;
import com.drones.vision.warehouse.domain.model.InventoryState;
import com.drones.vision.warehouse.domain.port.AssetLiveStatePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code assetService} is a Mockito mock; {@link VehicleConfigPort}, {@link AssetLiveStatePort} and
 * {@link AuditTrailPort} are hand-rolled in-memory fakes, mirroring this package's other service
 * tests.
 */
class DefaultRemediationServiceTest {

    private static final CategoryId DRONE = new CategoryId("drone");

    private AssetService assetService;
    private FakeAssetLiveStatePort liveState;
    private FakeVehicleConfigPort vehicleConfigPort;
    private FakeAuditTrailPort auditTrail;
    private DefaultRemediationService service;

    private final UserId actor = UserId.random();
    private final AssetId assetId = AssetId.random();
    private Device device;

    @BeforeEach
    void setUp() {
        assetService = mock(AssetService.class);
        liveState = new FakeAssetLiveStatePort();
        vehicleConfigPort = new FakeVehicleConfigPort();
        auditTrail = new FakeAuditTrailPort();
        service = new DefaultRemediationService(assetService, liveState, vehicleConfigPort, auditTrail);

        Map<String, String> options = new HashMap<>();
        options.put("sysid", "7");
        device = new Device(DeviceId.random(), "FC", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("mavlink", URI.create("udp://127.0.0.1:14550"), options));
    }

    private void stubDetails(Ownership ownership, Device... devices) {
        Asset asset = Asset.register(assetId, "Drone 1", DRONE, ownership, Set.of(devices[0].id()), Map.of(),
                Identity.NONE, Custody.NONE);
        AssetSummary summary = new AssetSummary(asset, "Drone", AssetStatus.OFFLINE, null, null,
                InventoryState.IN_STOCK, Identity.NONE, Custody.NONE);
        when(assetService.details(assetId)).thenReturn(new AssetDetails(summary, List.of(devices), List.of()));
    }

    private void stubArmed(Boolean armed) {
        FlightState state = armed == null
                ? FlightState.empty()
                : new FlightState(null, null, armed, null, null, null, null, null, List.of());
        liveState.telemetry.put(assetId, new Telemetry(device.id(), Instant.parse("2026-08-18T00:00:00Z"),
                null, null, null, null, null, Map.of(), state, null, null, null));
    }

    // -- writeParameter: tier gating ------------------------------------------------

    /** Required behavior: a Tier-C parameter name is rejected by the allowlist. */
    @Test
    void writeParameterRejectsATierCNameBeforeTouchingAnythingElse() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.writeParameter(assetId, "ARMING_CHECK", 1.0, false, actor, VisibilityScope.unbounded()));
        assertTrue(ex.getMessage().contains("ARMING_CHECK"));

        assertTrue(vehicleConfigPort.writtenParams.isEmpty());
        assertTrue(auditTrail.recorded.isEmpty());
    }

    @Test
    void writeParameterRejectsAnUnclassifiedNameJustLikeTierC() {
        assertThrows(IllegalArgumentException.class,
                () -> service.writeParameter(assetId, "TOTALLY_MADE_UP_PARAM", 1.0, false, actor,
                        VisibilityScope.unbounded()));
        assertTrue(auditTrail.recorded.isEmpty());
    }

    // -- writeParameter: disarmed-only (D10) ---------------------------------------

    /** Required behavior: a write is refused while armed == true. */
    @Test
    void writeParameterRefusedWhileArmedIsTrue() {
        stubDetails(new Ownership(actor, GroupId.random()), device);
        stubArmed(true);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.writeParameter(assetId, "SYSID_THISMAV", 3.0, false, actor, VisibilityScope.unbounded()));
        assertTrue(ex.getMessage().contains("armed"));

        assertTrue(vehicleConfigPort.writtenParams.isEmpty());
        assertEquals(1, auditTrail.recorded.size());
        assertEquals("REFUSED:aircraft is armed", auditTrail.recorded.get(0).details().get("result"));
    }

    /** Required behavior: a write is refused equally while armed == null -- unknown is not permission. */
    @Test
    void writeParameterRefusedWhileArmedIsUnknown() {
        stubDetails(new Ownership(actor, GroupId.random()), device);
        stubArmed(null);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.writeParameter(assetId, "SYSID_THISMAV", 3.0, false, actor, VisibilityScope.unbounded()));
        assertTrue(ex.getMessage().contains("unknown"));

        assertTrue(vehicleConfigPort.writtenParams.isEmpty());
        assertEquals(1, auditTrail.recorded.size());
        assertEquals("REFUSED:arming state unknown", auditTrail.recorded.get(0).details().get("result"));
    }

    @Test
    void writeParameterRefusedWhenNoTelemetryHasEverArrivedAtAll() {
        stubDetails(new Ownership(actor, GroupId.random()), device);
        // liveState has no entry for assetId at all -- the "never heard from" case, distinct from
        // an explicit FlightState with armed == null, and must refuse identically.

        assertThrows(IllegalStateException.class,
                () -> service.writeParameter(assetId, "SYSID_THISMAV", 3.0, false, actor, VisibilityScope.unbounded()));
        assertEquals("REFUSED:arming state unknown", auditTrail.recorded.get(0).details().get("result"));
    }

    // -- writeParameter: tier A happy path + authority ------------------------------

    @Test
    void writeParameterTierASucceedsWhenDisarmedAndInScope() {
        stubDetails(new Ownership(actor, GroupId.random()), device);
        stubArmed(false);
        vehicleConfigPort.writeResult =
                new ParameterWriteOutcome("SYSID_THISMAV", RemediationResultCode.ACCEPTED, 1.0, 3.0, null);

        ParameterWriteOutcome outcome =
                service.writeParameter(assetId, "SYSID_THISMAV", 3.0, false, actor, VisibilityScope.unbounded());

        assertEquals(RemediationResultCode.ACCEPTED, outcome.outcome());
        assertEquals(List.of("SYSID_THISMAV"), vehicleConfigPort.writtenParams);
        assertEquals("ACCEPTED", auditTrail.recorded.get(0).details().get("result"));
    }

    @Test
    void writeParameterTierADeniedWhenOutOfManagementScope() {
        stubDetails(new Ownership(actor, GroupId.random()), device);
        stubArmed(false);

        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> service.writeParameter(assetId, "SYSID_THISMAV", 3.0, false, actor,
                        VisibilityScope.groups(Set.of())));
        assertTrue(ex.getMessage().contains(assetId.value().toString()));
        assertTrue(vehicleConfigPort.writtenParams.isEmpty());
        assertEquals("DENIED:out of scope", auditTrail.recorded.get(0).details().get("result"));
    }

    @Test
    void writeParameterThrowsNoSuchElementForAnUnknownAsset() {
        when(assetService.details(assetId)).thenThrow(new NoSuchElementException("Unknown asset: " + assetId.value()));

        assertThrows(NoSuchElementException.class,
                () -> service.writeParameter(assetId, "SYSID_THISMAV", 3.0, false, actor, VisibilityScope.unbounded()));
        assertTrue(auditTrail.recorded.isEmpty());
    }

    // -- writeParameter: tier B authority + consent --------------------------------

    @Test
    void writeParameterTierBRequiresCanAdministerNotJustCanManage() {
        GroupId group = GroupId.random();
        stubDetails(new Ownership(actor, group), device);
        stubArmed(false);

        assertThrows(AccessDeniedException.class,
                () -> service.writeParameter(assetId, "FS_GCS_ENABLE", 1.0, true, actor,
                        VisibilityScope.groups(Set.of(group))));
        assertTrue(vehicleConfigPort.writtenParams.isEmpty());
    }

    @Test
    void writeParameterTierBWithoutExplicitConsentIsRejectedAsIllegalArgument() {
        stubDetails(new Ownership(actor, GroupId.random()), device);
        stubArmed(false);

        assertThrows(IllegalArgumentException.class,
                () -> service.writeParameter(assetId, "FS_GCS_ENABLE", 1.0, false, actor, VisibilityScope.unbounded()));
        assertTrue(vehicleConfigPort.writtenParams.isEmpty());
    }

    @Test
    void writeParameterTierBWithConsentAndAdministerAuthoritySucceeds() {
        stubDetails(new Ownership(actor, GroupId.random()), device);
        stubArmed(false);
        vehicleConfigPort.writeResult =
                new ParameterWriteOutcome("FS_GCS_ENABLE", RemediationResultCode.ACCEPTED, 0.0, 1.0, null);

        ParameterWriteOutcome outcome =
                service.writeParameter(assetId, "FS_GCS_ENABLE", 1.0, true, actor, VisibilityScope.unbounded());

        assertEquals(RemediationResultCode.ACCEPTED, outcome.outcome());
    }

    // -- requestMessageInterval (Mechanism A) --------------------------------------

    @Test
    void requestMessageIntervalSucceedsWhenDisarmedAndInScope() {
        stubDetails(new Ownership(actor, GroupId.random()), device);
        stubArmed(false);
        vehicleConfigPort.intervalResult =
                new MessageIntervalOutcome(74, Duration.ofMillis(200), RemediationResultCode.ACCEPTED, null);

        MessageIntervalOutcome outcome = service.requestMessageInterval(assetId, 74, Duration.ofMillis(200), actor,
                VisibilityScope.unbounded());

        assertEquals(RemediationResultCode.ACCEPTED, outcome.outcome());
        assertEquals("ACCEPTED", auditTrail.recorded.get(0).details().get("result"));
    }

    @Test
    void requestMessageIntervalRefusedWhileArmed() {
        stubDetails(new Ownership(actor, GroupId.random()), device);
        stubArmed(true);

        assertThrows(IllegalStateException.class, () -> service.requestMessageInterval(assetId, 74,
                Duration.ofMillis(200), actor, VisibilityScope.unbounded()));
        assertEquals("REFUSED:aircraft is armed", auditTrail.recorded.get(0).details().get("result"));
    }

    @Test
    void requestMessageIntervalDeniedWhenOutOfManagementScope() {
        stubDetails(new Ownership(actor, GroupId.random()), device);

        assertThrows(AccessDeniedException.class, () -> service.requestMessageInterval(assetId, 74,
                Duration.ofMillis(200), actor, VisibilityScope.groups(Set.of())));
        assertEquals("DENIED:out of scope", auditTrail.recorded.get(0).details().get("result"));
    }

    // -- device resolution ----------------------------------------------------------

    @Test
    void writeParameterThrowsIllegalStateWhenNoDeviceIsConfigurable() {
        stubDetails(new Ownership(actor, GroupId.random()), device);
        stubArmed(false);
        vehicleConfigPort.supportsResult = false;

        assertThrows(IllegalStateException.class,
                () -> service.writeParameter(assetId, "SYSID_THISMAV", 3.0, false, actor, VisibilityScope.unbounded()));
        assertTrue(vehicleConfigPort.writtenParams.isEmpty());
    }

    // -- test doubles -----------------------------------------------------------

    private static final class FakeVehicleConfigPort implements VehicleConfigPort {
        boolean supportsResult = true;
        ParameterWriteOutcome writeResult;
        MessageIntervalOutcome intervalResult;
        final List<String> writtenParams = new ArrayList<>();

        @Override
        public boolean supports(Device device) {
            return supportsResult;
        }

        @Override
        public com.drones.vision.flight.domain.model.VehicleProfile probe(String linkKey, Duration window) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MessageIntervalOutcome requestMessageInterval(String linkKey, int messageId, Duration interval) {
            return intervalResult;
        }

        @Override
        public List<ParameterReading> readParams(String linkKey, List<String> parameterNames) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ParameterWriteOutcome writeParam(String linkKey, String parameterName, double value) {
            writtenParams.add(parameterName);
            return writeResult;
        }
    }

    private static final class FakeAssetLiveStatePort implements AssetLiveStatePort {
        final Map<AssetId, Telemetry> telemetry = new HashMap<>();

        @Override
        public Map<DeviceId, com.drones.vision.kernel.StreamId> activeStreamsByDevice() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int stopStreamsForDevices(Collection<DeviceId> deviceIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Telemetry> latestTelemetry(AssetId assetId) {
            return Optional.ofNullable(telemetry.get(assetId));
        }

        @Override
        public Map<AssetId, Integer> openDetectionEventCounts(int scanLimit) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeAuditTrailPort implements AuditTrailPort {
        final List<AuditEntry> recorded = new ArrayList<>();

        @Override
        public AuditEntry record(AuditEntry entry) {
            recorded.add(entry);
            return entry;
        }

        @Override
        public List<AuditEntry> findRecent(int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AuditEntry> findByTarget(AuditTargetType targetType, String targetId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AuditEntry> findByActor(UserId actor, int limit) {
            throw new UnsupportedOperationException();
        }
    }
}

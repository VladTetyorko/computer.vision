package com.drones.vision.flight.application;

import com.drones.vision.flight.domain.model.MessageIntervalOutcome;
import com.drones.vision.flight.domain.model.ParameterReading;
import com.drones.vision.flight.domain.model.ParameterWriteOutcome;
import com.drones.vision.flight.domain.model.VehicleProfile;
import com.drones.vision.flight.domain.port.VehicleConfigPort;
import com.drones.vision.flight.domain.port.VehicleProfileRepositoryPort;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.StreamDescriptor;
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
import com.drones.vision.warehouse.domain.model.Device;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * {@code assetService} is a Mockito mock (mirrors {@code DefaultFlightCommandServiceTest} -- a large
 * warehouse interface this service only ever calls {@code details} on); {@link VehicleConfigPort}
 * and {@link AuditTrailPort} are hand-rolled in-memory fakes, matching {@code
 * DefaultManualControlServiceTest}'s own convention.
 */
class DefaultVehicleProfileServiceTest {

    private static final CategoryId DRONE = new CategoryId("drone");
    private static final Duration WINDOW = Duration.ofSeconds(10);

    private AssetService assetService;
    private FakeVehicleConfigPort vehicleConfigPort;
    private FakeVehicleProfileRepositoryPort profileRepository;
    private FakeAuditTrailPort auditTrail;
    private DefaultVehicleProfileService service;

    private final UserId actor = UserId.random();
    private final AssetId assetId = AssetId.random();
    private Device device;

    @BeforeEach
    void setUp() {
        assetService = mock(AssetService.class);
        vehicleConfigPort = new FakeVehicleConfigPort();
        profileRepository = new FakeVehicleProfileRepositoryPort();
        auditTrail = new FakeAuditTrailPort();
        service = new DefaultVehicleProfileService(assetService, vehicleConfigPort, profileRepository, auditTrail);

        Map<String, String> options = new HashMap<>();
        options.put("sysid", "7");
        device = new Device(DeviceId.random(), "FC", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("mavlink", URI.create("udp://127.0.0.1:14550"), options));
    }

    private void stubDetails(Device... devices) {
        stubDetailsWithOwnership(new Ownership(actor, GroupId.random()), devices);
    }

    private void stubDetailsWithOwnership(Ownership ownership, Device... devices) {
        Asset asset = new Asset(assetId, "Drone 1", DRONE, ownership, Set.of(devices[0].id()), Map.of());
        AssetSummary summary = new AssetSummary(asset, "Drone", AssetStatus.OFFLINE, null, null);
        AssetDetails details = new AssetDetails(summary, List.of(devices), List.of());
        when(assetService.details(assetId)).thenReturn(details);
        when(assetService.details(org.mockito.ArgumentMatchers.any(VisibilityScope.class),
                org.mockito.ArgumentMatchers.eq(assetId))).thenReturn(details);
    }

    private static VehicleProfile completeProfile() {
        return new VehicleProfile("udp://127.0.0.1:14550#7", Instant.parse("2026-08-18T00:00:00Z"), 7,
                "ardupilot", "4.5.7", "quadcopter", 12345L, List.of("MAVLINK2"), List.of(),
                List.of(new ParameterReading("SR2_EXTRA2", 0.0, "REAL32")), 2300L, true, null);
    }

    // -- probe (active, per-asset) ------------------------------------------------

    @Test
    void probeResolvesDeviceCallsThePortPersistsAndAuditsSuccess() {
        stubDetails(device);
        VehicleProfile profile = completeProfile();
        vehicleConfigPort.probeResult = profile;

        VehicleProfile result = service.probe(assetId, WINDOW, actor, VisibilityScope.unbounded());

        assertEquals(profile, result);
        assertEquals(List.of("udp://127.0.0.1:14550#7"), vehicleConfigPort.probedLinkKeys);
        assertEquals(profile, profileRepository.findLatest(device.id()).orElseThrow());

        assertEquals(1, auditTrail.recorded.size());
        AuditEntry entry = auditTrail.recorded.get(0);
        assertEquals(AuditTargetType.ASSET, entry.targetType());
        assertEquals("PROBE", entry.details().get("command"));
        assertEquals("COMPLETE", entry.details().get("result"));
    }

    @Test
    void probeAuditsIncompleteResultWithTheReason() {
        stubDetails(device);
        VehicleProfile incomplete = new VehicleProfile("udp://127.0.0.1:14550#7",
                Instant.parse("2026-08-18T00:00:00Z"), 7, null, null, null, null, List.of(), List.of(), List.of(),
                null, false, "AUTOPILOT_VERSION not answered within 3s");
        vehicleConfigPort.probeResult = incomplete;

        service.probe(assetId, WINDOW, actor, VisibilityScope.unbounded());

        assertEquals("INCOMPLETE:AUTOPILOT_VERSION not answered within 3s",
                auditTrail.recorded.get(0).details().get("result"));
    }

    /** Required behavior: an out-of-scope probe is refused (403) AND an audit entry is written. */
    @Test
    void probeDeniedWhenAssetIsOutOfScopeAndAuditsTheDenial() {
        stubDetails(device);

        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> service.probe(assetId, WINDOW, actor, VisibilityScope.groups(Set.of())));
        assertTrue(ex.getMessage().contains(assetId.value().toString()));

        assertTrue(vehicleConfigPort.probedLinkKeys.isEmpty());
        assertEquals(1, auditTrail.recorded.size());
        assertEquals("DENIED:out of scope", auditTrail.recorded.get(0).details().get("result"));
    }

    @Test
    void probeAllowedWhenAssetIsWithinAManagedGroupScope() {
        GroupId group = GroupId.random();
        stubDetailsWithOwnership(new Ownership(actor, group), device);
        vehicleConfigPort.probeResult = completeProfile();

        VehicleProfile result = service.probe(assetId, WINDOW, actor, VisibilityScope.groups(Set.of(group)));

        assertEquals(completeProfile(), result);
        assertEquals(1, vehicleConfigPort.probedLinkKeys.size());
    }

    @Test
    void probeThrowsNoSuchElementForAnUnknownAssetAndDoesNotAudit() {
        when(assetService.details(assetId)).thenThrow(new NoSuchElementException("Unknown asset: " + assetId.value()));

        assertThrows(NoSuchElementException.class,
                () -> service.probe(assetId, WINDOW, actor, VisibilityScope.unbounded()));
        assertTrue(auditTrail.recorded.isEmpty());
    }

    @Test
    void probeThrowsIllegalStateWhenNoDeviceIsSupportedAndDoesNotAudit() {
        stubDetails(device);
        vehicleConfigPort.supportsResult = false;

        assertThrows(IllegalStateException.class,
                () -> service.probe(assetId, WINDOW, actor, VisibilityScope.unbounded()));
        assertTrue(vehicleConfigPort.probedLinkKeys.isEmpty());
        assertTrue(auditTrail.recorded.isEmpty());
    }

    // -- latestProfile (scoped read) -----------------------------------------------

    @Test
    void latestProfileReturnsThePersistedSnapshot() {
        stubDetails(device);
        VehicleProfile profile = completeProfile();
        profileRepository.save(device.id(), profile);

        assertEquals(profile, service.latestProfile(assetId, VisibilityScope.unbounded()));
    }

    /** Required behavior: an out-of-scope read is a not-found, not a denial. */
    @Test
    void latestProfileThrowsNoSuchElementWhenTheScopedReadHidesTheAsset() {
        when(assetService.details(org.mockito.ArgumentMatchers.any(VisibilityScope.class),
                org.mockito.ArgumentMatchers.eq(assetId)))
                .thenThrow(new NoSuchElementException("Unknown asset: " + assetId.value()));

        assertThrows(NoSuchElementException.class,
                () -> service.latestProfile(assetId, VisibilityScope.groups(Set.of())));
        assertTrue(auditTrail.recorded.isEmpty());
    }

    @Test
    void latestProfileThrowsNoSuchElementWhenNeverProbed() {
        stubDetails(device);

        assertThrows(NoSuchElementException.class,
                () -> service.latestProfile(assetId, VisibilityScope.unbounded()));
    }

    // -- probeCandidate (pre-registration) -----------------------------------------

    @Test
    void probeCandidateCallsThePortDirectlyWithNoAssetInvolved() {
        VehicleProfile profile = completeProfile();
        vehicleConfigPort.probeResult = profile;

        VehicleProfile result = service.probeCandidate("udp://0.0.0.0:14550#7", WINDOW, actor);

        assertEquals(profile, result);
        assertEquals(List.of("udp://0.0.0.0:14550#7"), vehicleConfigPort.probedLinkKeys);
    }

    // -- test doubles ---------------------------------------------------------------

    private static final class FakeVehicleConfigPort implements VehicleConfigPort {
        boolean supportsResult = true;
        VehicleProfile probeResult;
        final List<String> probedLinkKeys = new ArrayList<>();

        @Override
        public boolean supports(Device device) {
            return supportsResult;
        }

        @Override
        public VehicleProfile probe(String linkKey, Duration window) {
            probedLinkKeys.add(linkKey);
            return probeResult;
        }

        @Override
        public MessageIntervalOutcome requestMessageInterval(String linkKey, int messageId, Duration interval) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ParameterReading> readParams(String linkKey, List<String> parameterNames) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ParameterWriteOutcome writeParam(String linkKey, String parameterName, double value) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeVehicleProfileRepositoryPort implements VehicleProfileRepositoryPort {
        private final Map<DeviceId, VehicleProfile> latest = new HashMap<>();

        @Override
        public void save(DeviceId deviceId, VehicleProfile profile) {
            latest.put(deviceId, profile);
        }

        @Override
        public Optional<VehicleProfile> findLatest(DeviceId deviceId) {
            return Optional.ofNullable(latest.get(deviceId));
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

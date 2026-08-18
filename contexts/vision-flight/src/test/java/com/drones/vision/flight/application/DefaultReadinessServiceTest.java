package com.drones.vision.flight.application;

import com.drones.vision.flight.domain.model.FeatureRequirement;
import com.drones.vision.flight.domain.model.FeatureStatus;
import com.drones.vision.flight.domain.model.FlightPhase;
import com.drones.vision.flight.domain.model.MessageObservation;
import com.drones.vision.flight.domain.model.ReadinessReport;
import com.drones.vision.flight.domain.model.ReadinessVerdict;
import com.drones.vision.flight.domain.model.VehicleProfile;
import com.drones.vision.flight.domain.port.FeatureRequirementRepositoryPort;
import com.drones.vision.flight.domain.port.VehicleProfileRepositoryPort;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.kernel.UserId;
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
import java.time.Instant;
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
 * {@code assetService} is a Mockito mock; {@link VehicleProfileRepositoryPort} and {@link
 * FeatureRequirementRepositoryPort} are hand-rolled in-memory fakes, mirroring this package's other
 * service tests. The clock is an injected {@code Supplier<Instant>} constant, never {@code
 * Instant.now()}.
 */
class DefaultReadinessServiceTest {

    private static final CategoryId DRONE = new CategoryId("drone");
    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");
    private static final int GLOBAL_POSITION_INT = 33;
    private static final int VFR_HUD = 74;

    private AssetService assetService;
    private FakeVehicleProfileRepositoryPort profileRepository;
    private FakeFeatureRequirementRepositoryPort requirementRepository;
    private DefaultReadinessService service;

    private final AssetId assetId = AssetId.random();
    private Device device;

    @BeforeEach
    void setUp() {
        assetService = mock(AssetService.class);
        profileRepository = new FakeVehicleProfileRepositoryPort();
        requirementRepository = new FakeFeatureRequirementRepositoryPort();
        service = new DefaultReadinessService(assetService, profileRepository, requirementRepository, () -> NOW);

        device = new Device(DeviceId.random(), "FC", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("mavlink", URI.create("udp://127.0.0.1:14550"), Map.of()));
        Asset asset = new Asset(assetId, "Drone 1", DRONE, new Ownership(UserId.random(), GroupId.random()),
                Set.of(device.id()), Map.of());
        AssetSummary summary = new AssetSummary(asset, "Drone", AssetStatus.OFFLINE, null, null);
        when(assetService.details(org.mockito.ArgumentMatchers.any(VisibilityScope.class),
                org.mockito.ArgumentMatchers.eq(assetId)))
                .thenReturn(new AssetDetails(summary, List.of(device), List.of()));
    }

    private static VehicleProfile profile(boolean complete, String incompleteReason,
                                           List<MessageObservation> messages) {
        return new VehicleProfile("udp://127.0.0.1:14550#7", Instant.parse("2026-08-18T11:00:00Z"), 7,
                "ardupilot", "4.5.7", "quadcopter", 0L, List.of(), messages, List.of(), 2300L, complete,
                incompleteReason);
    }

    /** Required behavior: an incomplete profile yields UNKNOWN, never GO. */
    @Test
    void incompleteProfileYieldsUnknownVerdictNeverGo() {
        profileRepository.save(device.id(), profile(false, "AUTOPILOT_VERSION not answered within 3s", List.of()));

        ReadinessReport report = service.evaluate(assetId, VisibilityScope.unbounded());

        assertEquals(ReadinessVerdict.UNKNOWN, report.verdict());
        assertTrue(report.features().stream().allMatch(f -> f.status() == FeatureStatus.UNKNOWN));
    }

    @Test
    void neverProbedAssetYieldsUnknownVerdictWithNullProfileObservedAt() {
        ReadinessReport report = service.evaluate(assetId, VisibilityScope.unbounded());

        assertEquals(ReadinessVerdict.UNKNOWN, report.verdict());
        assertEquals(null, report.profileObservedAt());
        assertEquals(FeatureRequirement.FEATURE_KEYS.size(), report.features().size());
    }

    @Test
    void completeProfileMeetingEveryRequirementIsGo() {
        requirementRepository.rows.add(new FeatureRequirement("ground-speed", "Ground speed", "ardupilot",
                VFR_HUD, "VFR_HUD", 2.0, null));
        profileRepository.save(device.id(),
                profile(true, null, List.of(new MessageObservation(VFR_HUD, "VFR_HUD", 5.0, 50))));

        ReadinessReport report = service.evaluate(assetId, VisibilityScope.unbounded());

        assertEquals(ReadinessVerdict.GO, report.verdict());
        assertTrue(report.blockers().isEmpty());
        FeatureStatus groundSpeed = report.features().stream()
                .filter(f -> f.featureKey().equals("ground-speed")).findFirst().orElseThrow().status();
        assertEquals(FeatureStatus.READY, groundSpeed);
    }

    @Test
    void missingRequiredMessageForcesNoGoAndBecomesABlocker() {
        requirementRepository.rows.add(new FeatureRequirement("ground-speed", "Ground speed", "ardupilot",
                VFR_HUD, "VFR_HUD", 2.0, null));
        profileRepository.save(device.id(), profile(true, null, List.of())); // VFR_HUD never arrived

        ReadinessReport report = service.evaluate(assetId, VisibilityScope.unbounded());

        assertEquals(ReadinessVerdict.NO_GO, report.verdict());
        assertEquals(List.of("ground-speed"), report.blockers());
    }

    @Test
    void messageBelowMinimumHzIsDegradedNotMissingAndDoesNotForceNoGoByItself() {
        requirementRepository.rows.add(new FeatureRequirement("ground-speed", "Ground speed", "ardupilot",
                VFR_HUD, "VFR_HUD", 2.0, null));
        profileRepository.save(device.id(),
                profile(true, null, List.of(new MessageObservation(VFR_HUD, "VFR_HUD", 0.5, 5))));

        ReadinessReport report = service.evaluate(assetId, VisibilityScope.unbounded());

        assertEquals(ReadinessVerdict.GO, report.verdict());
        FeatureStatus groundSpeed = report.features().stream()
                .filter(f -> f.featureKey().equals("ground-speed")).findFirst().orElseThrow().status();
        assertEquals(FeatureStatus.DEGRADED, groundSpeed);
    }

    @Test
    void unknownFirmwareWithNoRequirementRowsMarksEveryFeatureUnknown() {
        profileRepository.save(device.id(), profile(true, null, List.of()));
        // requirementRepository has zero rows for "ardupilot" -- simulates a firmware never seen.

        ReadinessReport report = service.evaluate(assetId, VisibilityScope.unbounded());

        assertTrue(report.features().stream().allMatch(f -> f.status() == FeatureStatus.UNKNOWN));
        assertEquals(ReadinessVerdict.GO, report.verdict()); // no MISSING blockers -- see class javadoc
    }

    @Test
    void evaluateThrowsNoSuchElementWhenTheScopedReadHidesTheAsset() {
        when(assetService.details(org.mockito.ArgumentMatchers.any(VisibilityScope.class),
                org.mockito.ArgumentMatchers.eq(assetId)))
                .thenThrow(new NoSuchElementException("Unknown asset: " + assetId.value()));

        assertThrows(NoSuchElementException.class, () -> service.evaluate(assetId, VisibilityScope.groups(Set.of())));
    }

    // -- test doubles -----------------------------------------------------------

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

        @Override
        public void save(DeviceId deviceId, UsageId usageId, FlightPhase phase, VehicleProfile profile) {
            throw new UnsupportedOperationException("not exercised by DefaultReadinessServiceTest");
        }

        @Override
        public Optional<VehicleProfile> findByUsageAndPhase(UsageId usageId, FlightPhase phase) {
            throw new UnsupportedOperationException("not exercised by DefaultReadinessServiceTest");
        }
    }

    private static final class FakeFeatureRequirementRepositoryPort implements FeatureRequirementRepositoryPort {
        final List<FeatureRequirement> rows = new java.util.ArrayList<>();

        @Override
        public List<FeatureRequirement> findByFirmware(String firmware) {
            return rows.stream().filter(r -> r.firmware().equals(firmware)).toList();
        }

        @Override
        public List<FeatureRequirement> findAll() {
            return List.copyOf(rows);
        }
    }
}

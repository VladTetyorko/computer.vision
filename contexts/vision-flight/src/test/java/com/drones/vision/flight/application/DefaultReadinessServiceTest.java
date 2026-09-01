package com.drones.vision.flight.application;

import com.drones.vision.flight.domain.model.FeatureRequirement;
import com.drones.vision.flight.domain.model.FeatureStatus;
import com.drones.vision.flight.domain.model.FlightPhase;
import com.drones.vision.flight.domain.model.MessageObservation;
import com.drones.vision.flight.domain.model.ParameterReading;
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
import com.drones.vision.warehouse.application.maintenance.MaintenanceQuery;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.warehouse.domain.model.Identity;
import com.drones.vision.warehouse.domain.model.InventoryState;
import com.drones.vision.warehouse.domain.model.MaintenanceId;
import com.drones.vision.warehouse.domain.model.MaintenanceKind;
import com.drones.vision.warehouse.domain.model.MaintenanceRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
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
    private FakeMaintenanceQuery maintenanceQuery;
    private DefaultReadinessService service;

    private final AssetId assetId = AssetId.random();
    private Device device;

    @BeforeEach
    void setUp() {
        assetService = mock(AssetService.class);
        profileRepository = new FakeVehicleProfileRepositoryPort();
        requirementRepository = new FakeFeatureRequirementRepositoryPort();
        maintenanceQuery = new FakeMaintenanceQuery();
        service = new DefaultReadinessService(assetService, profileRepository, requirementRepository,
                maintenanceQuery, () -> NOW);

        device = new Device(DeviceId.random(), "FC", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("mavlink", URI.create("udp://127.0.0.1:14550"), Map.of()));
        Asset asset = Asset.register(assetId, "Drone 1", DRONE, new Ownership(UserId.random(), GroupId.random()),
                Set.of(device.id()), Map.of(), Identity.NONE, Custody.NONE);
        AssetSummary summary = new AssetSummary(asset, "Drone", AssetStatus.OFFLINE, null, null,
                InventoryState.IN_STOCK, Identity.NONE, Custody.NONE);
        when(assetService.details(org.mockito.ArgumentMatchers.any(VisibilityScope.class),
                org.mockito.ArgumentMatchers.eq(assetId)))
                .thenReturn(new AssetDetails(summary, List.of(device), List.of()));
    }

    /** One open, flight-blocking {@code GROUNDING} record for {@link #assetId}, opened just now. */
    private MaintenanceRecord openGroundingRecord(String summary) {
        return new MaintenanceRecord(MaintenanceId.random(), assetId, MaintenanceKind.GROUNDING, NOW, null,
                UserId.random(), summary, null);
    }

    private static VehicleProfile profile(boolean complete, String incompleteReason,
                                           List<MessageObservation> messages) {
        return profile(complete, incompleteReason, messages, List.of());
    }

    private static VehicleProfile profile(boolean complete, String incompleteReason,
                                           List<MessageObservation> messages,
                                           List<ParameterReading> parameters) {
        return new VehicleProfile("udp://127.0.0.1:14550#7", Instant.parse("2026-08-18T11:00:00Z"), 7,
                "ardupilot", "4.5.7", "quadcopter", 0L, List.of(), messages, parameters, 2300L, complete,
                incompleteReason);
    }

    /** The seeded {@code fleet-identity} row: a parameter requirement, no message requirement. */
    private static FeatureRequirement fleetIdentityRequiring(String parameterName) {
        return new FeatureRequirement("fleet-identity", "Fleet identity", "ardupilot",
                null, null, null, parameterName, null, null);
    }

    /** V27's GCS-sysid row: {@code rc-relay} requires this platform's own sysid, 255. */
    private static FeatureRequirement rcRelayGcsSysidRequiring(String parameterName) {
        return new FeatureRequirement("rc-relay", "RC relay (GCS sysid)", "ardupilot",
                null, null, null, parameterName, 255.0, null);
    }

    /** V27's RC_OPTIONS row: {@code rc-relay} forbids bit 1 (IGNORE_OVERRIDES). */
    private static FeatureRequirement rcRelayRcOptionsForbidding(long bits) {
        return new FeatureRequirement("rc-relay", "RC relay (RC_OPTIONS)", "ardupilot",
                null, null, null, "RC_OPTIONS", null, bits);
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
                VFR_HUD, "VFR_HUD", 2.0, null, null, null));
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
                VFR_HUD, "VFR_HUD", 2.0, null, null, null));
        profileRepository.save(device.id(), profile(true, null, List.of())); // VFR_HUD never arrived

        ReadinessReport report = service.evaluate(assetId, VisibilityScope.unbounded());

        assertEquals(ReadinessVerdict.NO_GO, report.verdict());
        assertEquals(List.of("ground-speed"), report.blockers());
    }

    @Test
    void messageBelowMinimumHzIsDegradedNotMissingAndDoesNotForceNoGoByItself() {
        requirementRepository.rows.add(new FeatureRequirement("ground-speed", "Ground speed", "ardupilot",
                VFR_HUD, "VFR_HUD", 2.0, null, null, null));
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

    // -- WAREHOUSE-UX-CONTEXT.md D6/OQ1: warehouse maintenance blockers -----------------------

    /** No maintenance records at all must leave the configuration-derived verdict untouched. */
    @Test
    void noMaintenanceRecordsLeavesTheVerdictUnchanged() {
        requirementRepository.rows.add(new FeatureRequirement("ground-speed", "Ground speed", "ardupilot",
                VFR_HUD, "VFR_HUD", 2.0, null, null, null));
        profileRepository.save(device.id(),
                profile(true, null, List.of(new MessageObservation(VFR_HUD, "VFR_HUD", 5.0, 50))));

        ReadinessReport report = service.evaluate(assetId, VisibilityScope.unbounded());

        assertEquals(ReadinessVerdict.GO, report.verdict());
        assertTrue(report.blockers().isEmpty());
    }

    /**
     * An open {@code GROUNDING} record forces {@link ReadinessVerdict#NO_GO} even on an otherwise
     * fully-ready asset, and the wire-visible blocker carries the record's own summary verbatim --
     * D6's whole point: a manager's grounding is a NO-GO a pilot can read the reason for.
     */
    @Test
    void openGroundingRecordForcesNoGoAndCarriesTheRecordsSummary() {
        requirementRepository.rows.add(new FeatureRequirement("ground-speed", "Ground speed", "ardupilot",
                VFR_HUD, "VFR_HUD", 2.0, null, null, null));
        profileRepository.save(device.id(),
                profile(true, null, List.of(new MessageObservation(VFR_HUD, "VFR_HUD", 5.0, 50))));
        maintenanceQuery.addRecord(assetId, openGroundingRecord("Propeller crack found on preflight"));

        ReadinessReport report = service.evaluate(assetId, VisibilityScope.unbounded());

        assertEquals(ReadinessVerdict.NO_GO, report.verdict());
        assertEquals(1, report.blockers().size());
        String blocker = report.blockers().get(0);
        assertTrue(blocker.startsWith(DefaultReadinessService.MAINTENANCE_BLOCKER_PREFIX), "got: " + blocker);
        assertTrue(blocker.contains("GROUNDING"), "got: " + blocker);
        assertTrue(blocker.contains("Propeller crack found on preflight"), "got: " + blocker);
    }

    /**
     * A maintenance blocker must win over an otherwise-{@code UNKNOWN} (never-probed) verdict too --
     * "grounded" is a harder fact than "nobody has probed this yet".
     */
    @Test
    void openGroundingRecordForcesNoGoEvenWhenNeverProbed() {
        maintenanceQuery.addRecord(assetId, openGroundingRecord("Grounded pending annual inspection"));

        ReadinessReport report = service.evaluate(assetId, VisibilityScope.unbounded());

        assertEquals(ReadinessVerdict.NO_GO, report.verdict());
        assertEquals(1, report.blockers().size());
    }

    /**
     * A closed record is not a blocker, even though this hand-rolled {@link FakeMaintenanceQuery}
     * deliberately does not pre-filter -- {@code DefaultReadinessService} itself must apply {@link
     * MaintenanceRecord#isOpen()} defensively, not merely trust {@link MaintenanceQuery#openBlockers}'s
     * own "open only" contract.
     */
    @Test
    void closedRecordIsNotABlocker() {
        requirementRepository.rows.add(new FeatureRequirement("ground-speed", "Ground speed", "ardupilot",
                VFR_HUD, "VFR_HUD", 2.0, null, null, null));
        profileRepository.save(device.id(),
                profile(true, null, List.of(new MessageObservation(VFR_HUD, "VFR_HUD", 5.0, 50))));
        MaintenanceRecord closed = openGroundingRecord("Repaired and returned to service").close(NOW);
        maintenanceQuery.addRecord(assetId, closed);

        ReadinessReport report = service.evaluate(assetId, VisibilityScope.unbounded());

        assertEquals(ReadinessVerdict.GO, report.verdict());
        assertTrue(report.blockers().isEmpty());
    }

    /**
     * An open {@code NOTE} record is informational, never a blocker -- same defensive posture as
     * {@link #closedRecordIsNotABlocker}, this time against {@link MaintenanceKind#blocksFlight()}
     * rather than {@link MaintenanceRecord#isOpen()}.
     */
    @Test
    void openNoteKindIsNotABlocker() {
        MaintenanceRecord note = new MaintenanceRecord(MaintenanceId.random(), assetId, MaintenanceKind.NOTE, NOW,
                null, UserId.random(), "Cleaned gimbal lens", null);
        maintenanceQuery.addRecord(assetId, note);

        ReadinessReport report = service.evaluate(assetId, VisibilityScope.unbounded());

        assertEquals(ReadinessVerdict.UNKNOWN, report.verdict()); // never probed, no blocker to override it
        assertTrue(report.blockers().isEmpty());
    }

    @Test
    void evaluateThrowsNoSuchElementWhenTheScopedReadHidesTheAsset() {
        when(assetService.details(org.mockito.ArgumentMatchers.any(VisibilityScope.class),
                org.mockito.ArgumentMatchers.eq(assetId)))
                .thenThrow(new NoSuchElementException("Unknown asset: " + assetId.value()));

        assertThrows(NoSuchElementException.class, () -> service.evaluate(assetId, VisibilityScope.groups(Set.of())));
    }

    // -- FLEET-RADIO-PLAN F0: a firmware rename must not read as a missing parameter ---------

    /**
     * Required behavior: ArduPilot 4.7+ answers {@code MAV_SYSID}, the seeded requirement row asks
     * for {@code SYSID_THISMAV}. Before alias-awareness this was a permanent {@code NO_GO} blocker
     * on every vehicle running current firmware.
     */
    @Test
    void currentFirmwareSpellingSatisfiesTheRequirementWrittenInTheOldSpelling() {
        requirementRepository.rows.add(fleetIdentityRequiring("SYSID_THISMAV"));
        profileRepository.save(device.id(), profile(true, null, List.of(),
                List.of(new ParameterReading("MAV_SYSID", 7.0, "INT32"))));

        ReadinessReport report = service.evaluate(assetId, VisibilityScope.unbounded());

        assertEquals(ReadinessVerdict.GO, report.verdict());
        assertTrue(report.blockers().isEmpty());
        assertEquals(FeatureStatus.READY, statusOf(report, "fleet-identity"));
    }

    /** The same row must keep passing for a vehicle on pre-4.7 firmware -- the fix is symmetric. */
    @Test
    void legacyFirmwareSpellingStillSatisfiesTheSameRequirement() {
        requirementRepository.rows.add(fleetIdentityRequiring("SYSID_THISMAV"));
        profileRepository.save(device.id(), profile(true, null, List.of(),
                List.of(new ParameterReading("SYSID_THISMAV", 7.0, "INT32"))));

        ReadinessReport report = service.evaluate(assetId, VisibilityScope.unbounded());

        assertEquals(ReadinessVerdict.GO, report.verdict());
        assertEquals(FeatureStatus.READY, statusOf(report, "fleet-identity"));
    }

    /** And symmetrically, were the row ever reseeded to the new spelling. */
    @Test
    void aRequirementWrittenInTheNewSpellingAcceptsTheOldReading() {
        requirementRepository.rows.add(fleetIdentityRequiring("MAV_SYSID"));
        profileRepository.save(device.id(), profile(true, null, List.of(),
                List.of(new ParameterReading("SYSID_THISMAV", 7.0, "INT32"))));

        assertEquals(ReadinessVerdict.GO, service.evaluate(assetId, VisibilityScope.unbounded()).verdict());
    }

    /** Alias-awareness must not make every parameter satisfy every requirement. */
    @Test
    void aGenuinelyAbsentParameterIsStillMissingAndStillBlocks() {
        requirementRepository.rows.add(fleetIdentityRequiring("SYSID_THISMAV"));
        profileRepository.save(device.id(), profile(true, null, List.of(),
                List.of(new ParameterReading("FRAME_CLASS", 1.0, "INT8"))));

        ReadinessReport report = service.evaluate(assetId, VisibilityScope.unbounded());

        assertEquals(ReadinessVerdict.NO_GO, report.verdict());
        assertEquals(List.of("fleet-identity"), report.blockers());
        assertEquals(FeatureStatus.MISSING, statusOf(report, "fleet-identity"));
    }

    private static FeatureStatus statusOf(ReadinessReport report, String featureKey) {
        return report.features().stream()
                .filter(f -> f.featureKey().equals(featureKey)).findFirst().orElseThrow().status();
    }

    private static com.drones.vision.flight.domain.model.FeatureReadiness readinessOf(ReadinessReport report,
                                                                                        String featureKey) {
        return report.features().stream().filter(f -> f.featureKey().equals(featureKey)).findFirst().orElseThrow();
    }

    // -- FLEET-RADIO-PLAN R6: value/bit-aware rc-relay checks --------------------

    /**
     * Required result 1: a GCS-sysid mismatch is MISSING with a PARAM_WRITE remedy -- this is also
     * the fix for the pre-existing bug where a parameter-only MISSING row never carried a remedy at
     * all (no prior test asserted remedy for a parameter row).
     */
    @Test
    void gcsSysidMismatchIsMissingWithParamWriteRemedy() {
        requirementRepository.rows.add(rcRelayGcsSysidRequiring("SYSID_MYGCS"));
        profileRepository.save(device.id(), profile(true, null, List.of(),
                List.of(new ParameterReading("SYSID_MYGCS", 1.0, "UINT8"))));

        ReadinessReport report = service.evaluate(assetId, VisibilityScope.unbounded());

        var readiness = readinessOf(report, "rc-relay");
        assertEquals(FeatureStatus.MISSING, readiness.status());
        assertEquals(com.drones.vision.flight.domain.model.RemedyKind.PARAM_WRITE, readiness.remedy());
        assertEquals(ReadinessVerdict.NO_GO, report.verdict());
    }

    @Test
    void gcsSysidMatchingThisPlatformsSysidIsReady() {
        requirementRepository.rows.add(rcRelayGcsSysidRequiring("SYSID_MYGCS"));
        profileRepository.save(device.id(), profile(true, null, List.of(),
                List.of(new ParameterReading("SYSID_MYGCS", 255.0, "UINT8"))));

        ReadinessReport report = service.evaluate(assetId, VisibilityScope.unbounded());

        assertEquals(FeatureStatus.READY, statusOf(report, "rc-relay"));
        assertEquals(ReadinessVerdict.GO, report.verdict());
    }

    /** F0's alias handling must also cover the value-aware path, not just presence. */
    @Test
    void gcsSysidCheckIsAliasAwareForTheFirmwareRenamedSpelling() {
        requirementRepository.rows.add(rcRelayGcsSysidRequiring("SYSID_MYGCS"));
        profileRepository.save(device.id(), profile(true, null, List.of(),
                List.of(new ParameterReading("MAV_GCS_SYSID", 255.0, "UINT8"))));

        assertEquals(FeatureStatus.READY, statusOf(service.evaluate(assetId, VisibilityScope.unbounded()), "rc-relay"));
    }

    /**
     * Required result 2: RC_OPTIONS bit 1 polarity, asserted by value in both directions -- not by
     * reading the constant's name, exactly as V27's migration comment demands.
     */
    @Test
    void rcOptionsBitOneSetIsMissing() {
        requirementRepository.rows.add(rcRelayRcOptionsForbidding(2L));
        profileRepository.save(device.id(), profile(true, null, List.of(),
                List.of(new ParameterReading("RC_OPTIONS", 2.0, "INT32")))); // bit 1 set

        var readiness = readinessOf(service.evaluate(assetId, VisibilityScope.unbounded()), "rc-relay");
        assertEquals(FeatureStatus.MISSING, readiness.status());
        assertEquals(com.drones.vision.flight.domain.model.RemedyKind.PARAM_WRITE, readiness.remedy());
    }

    @Test
    void rcOptionsBitOneClearIsReady() {
        requirementRepository.rows.add(rcRelayRcOptionsForbidding(2L));
        profileRepository.save(device.id(), profile(true, null, List.of(),
                List.of(new ParameterReading("RC_OPTIONS", 5.0, "INT32")))); // bits 0+2 set, bit 1 clear

        assertEquals(FeatureStatus.READY,
                statusOf(service.evaluate(assetId, VisibilityScope.unbounded()), "rc-relay"));
    }

    @Test
    void rcOptionsAtZeroIsReady() {
        requirementRepository.rows.add(rcRelayRcOptionsForbidding(2L));
        profileRepository.save(device.id(), profile(true, null, List.of(),
                List.of(new ParameterReading("RC_OPTIONS", 0.0, "INT32"))));

        assertEquals(FeatureStatus.READY,
                statusOf(service.evaluate(assetId, VisibilityScope.unbounded()), "rc-relay"));
    }

    /** A parameter never read at all is MISSING, same as the presence-only path. */
    @Test
    void rcOptionsNeverReadIsMissing() {
        requirementRepository.rows.add(rcRelayRcOptionsForbidding(2L));
        profileRepository.save(device.id(), profile(true, null, List.of(), List.of()));

        var readiness = readinessOf(service.evaluate(assetId, VisibilityScope.unbounded()), "rc-relay");
        assertEquals(FeatureStatus.MISSING, readiness.status());
        assertEquals(com.drones.vision.flight.domain.model.RemedyKind.PARAM_WRITE, readiness.remedy());
    }

    // -- combine(): two rows under one key must fold into one wire row ----------

    /** Both V27 rows satisfied -- the frozen-key decision must still yield exactly one row. */
    @Test
    void bothRcRelayRowsReadyCombineIntoOneReadyRow() {
        requirementRepository.rows.add(rcRelayGcsSysidRequiring("SYSID_MYGCS"));
        requirementRepository.rows.add(rcRelayRcOptionsForbidding(2L));
        profileRepository.save(device.id(), profile(true, null, List.of(), List.of(
                new ParameterReading("SYSID_MYGCS", 255.0, "UINT8"),
                new ParameterReading("RC_OPTIONS", 0.0, "INT32"))));

        ReadinessReport report = service.evaluate(assetId, VisibilityScope.unbounded());

        assertEquals(1, report.features().stream().filter(f -> f.featureKey().equals("rc-relay")).count());
        assertEquals(FeatureStatus.READY, statusOf(report, "rc-relay"));
        assertEquals(ReadinessVerdict.GO, report.verdict());
    }

    /** One row MISSING, the other READY: the combined row is MISSING, still exactly one row. */
    @Test
    void oneRcRelayRowMissingMakesTheCombinedRowMissing() {
        requirementRepository.rows.add(rcRelayGcsSysidRequiring("SYSID_MYGCS"));
        requirementRepository.rows.add(rcRelayRcOptionsForbidding(2L));
        profileRepository.save(device.id(), profile(true, null, List.of(), List.of(
                new ParameterReading("SYSID_MYGCS", 1.0, "UINT8"), // wrong -- MISSING
                new ParameterReading("RC_OPTIONS", 0.0, "INT32")))); // clear -- READY

        ReadinessReport report = service.evaluate(assetId, VisibilityScope.unbounded());

        assertEquals(1, report.features().stream().filter(f -> f.featureKey().equals("rc-relay")).count());
        var readiness = readinessOf(report, "rc-relay");
        assertEquals(FeatureStatus.MISSING, readiness.status());
        assertEquals(com.drones.vision.flight.domain.model.RemedyKind.PARAM_WRITE, readiness.remedy());
        assertTrue(readiness.detail().contains("SYSID_MYGCS"));
        assertEquals(List.of("rc-relay"), report.blockers());
    }

    /** Both rows MISSING: the combined detail joins both independent diagnoses. */
    @Test
    void bothRcRelayRowsMissingJoinsBothDetailsInOneRow() {
        requirementRepository.rows.add(rcRelayGcsSysidRequiring("SYSID_MYGCS"));
        requirementRepository.rows.add(rcRelayRcOptionsForbidding(2L));
        profileRepository.save(device.id(), profile(true, null, List.of(), List.of(
                new ParameterReading("SYSID_MYGCS", 1.0, "UINT8"),
                new ParameterReading("RC_OPTIONS", 2.0, "INT32"))));

        ReadinessReport report = service.evaluate(assetId, VisibilityScope.unbounded());

        var readiness = readinessOf(report, "rc-relay");
        assertEquals(FeatureStatus.MISSING, readiness.status());
        assertTrue(readiness.detail().contains("SYSID_MYGCS"));
        assertTrue(readiness.detail().contains("RC_OPTIONS"));
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

    /**
     * Deliberately does <b>not</b> pre-filter to open/blocking-only, unlike the real {@link
     * MaintenanceQuery#openBlockers} contract -- a test seeds whatever {@link MaintenanceRecord} it
     * wants via {@link #addRecord}, so {@code closedRecordIsNotABlocker}/{@code
     * openNoteKindIsNotABlocker} actually exercise {@code DefaultReadinessService}'s own defensive
     * filtering rather than merely re-testing a correctly-behaving fake.
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

package com.drones.vision.flight.application;

import com.drones.vision.flight.domain.model.FlightPassport;
import com.drones.vision.flight.domain.model.FlightPhase;
import com.drones.vision.flight.domain.model.MessageIntervalOutcome;
import com.drones.vision.flight.domain.model.ParameterDrift;
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
import com.drones.vision.kernel.UsageId;
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
import com.drones.vision.warehouse.domain.model.AssetUsage;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort;
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
 * warehouse interface this service only ever calls {@code details} on); {@link VehicleConfigPort},
 * {@link AssetUsageRepositoryPort} and {@link AuditTrailPort} are hand-rolled in-memory fakes,
 * matching {@code DefaultManualControlServiceTest}'s own convention.
 */
class DefaultVehicleProfileServiceTest {

    private static final CategoryId DRONE = new CategoryId("drone");
    private static final Duration WINDOW = Duration.ofSeconds(10);

    private AssetService assetService;
    private FakeVehicleConfigPort vehicleConfigPort;
    private FakeVehicleProfileRepositoryPort profileRepository;
    private FakeAssetUsageRepositoryPort assetUsageRepository;
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
        assetUsageRepository = new FakeAssetUsageRepositoryPort();
        auditTrail = new FakeAuditTrailPort();
        service = new DefaultVehicleProfileService(assetService, vehicleConfigPort, profileRepository,
                assetUsageRepository, auditTrail);

        Map<String, String> options = new HashMap<>();
        options.put("sysid", "7");
        device = new Device(DeviceId.random(), "FC", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("mavlink", URI.create("udp://127.0.0.1:14550"), options));
    }

    private void stubDetails(Device... devices) {
        stubDetailsWithOwnership(new Ownership(actor, GroupId.random()), devices);
    }

    private void stubDetailsWithOwnership(Ownership ownership, Device... devices) {
        stubDetailsWithUsages(ownership, List.of(), devices);
    }

    /**
     * Seeds both {@code AssetDetails#recentUsages()} (the capped list {@code
     * driftFromPreviousFlight}'s own "find the previous flight" traversal reads) and {@link
     * #assetUsageRepository} (the uncapped {@code findById} lookup {@code
     * requireUsageBelongsToAsset} now reads) from the same {@code usages} list -- correct for every
     * existing test here, since none of them exercises the two lists diverging. Tests that need a
     * usage known to {@code assetUsageRepository} but absent from the capped list (the whole point
     * of this wave's fix) seed {@link #assetUsageRepository} directly instead.
     */
    private void stubDetailsWithUsages(Ownership ownership, List<AssetUsage> usages, Device... devices) {
        Asset asset = new Asset(assetId, "Drone 1", DRONE, ownership, Set.of(devices[0].id()), Map.of());
        AssetSummary summary = new AssetSummary(asset, "Drone", AssetStatus.OFFLINE, null, null);
        AssetDetails details = new AssetDetails(summary, List.of(devices), usages);
        when(assetService.details(assetId)).thenReturn(details);
        when(assetService.details(org.mockito.ArgumentMatchers.any(VisibilityScope.class),
                org.mockito.ArgumentMatchers.eq(assetId))).thenReturn(details);
        usages.forEach(assetUsageRepository::save);
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

    // -- captureSnapshot (O11, the passport) -----------------------------------------

    @Test
    void captureSnapshotStoresATaggedSnapshotAndAuditsSuccess() {
        stubDetails(device);
        UsageId usageId = UsageId.random();
        VehicleProfile profile = completeProfile();
        vehicleConfigPort.probeResult = profile;

        VehicleProfile result =
                service.captureSnapshot(assetId, usageId, FlightPhase.PREFLIGHT, WINDOW, actor, VisibilityScope.unbounded());

        assertEquals(profile, result);
        assertEquals(profile, profileRepository.findByUsageAndPhase(usageId, FlightPhase.PREFLIGHT).orElseThrow());
        assertTrue(profileRepository.findByUsageAndPhase(usageId, FlightPhase.POSTFLIGHT).isEmpty());

        assertEquals(1, auditTrail.recorded.size());
        AuditEntry entry = auditTrail.recorded.get(0);
        assertEquals("CAPTURE_SNAPSHOT", entry.details().get("command"));
        assertEquals(usageId.value().toString(), entry.details().get("usageId"));
        assertEquals("PREFLIGHT", entry.details().get("phase"));
        assertEquals("COMPLETE", entry.details().get("result"));
    }

    @Test
    void captureSnapshotRejectsAPhaseThatIsNeitherPreflightNorPostflight() {
        assertThrows(IllegalArgumentException.class, () -> service.captureSnapshot(assetId, UsageId.random(),
                FlightPhase.IN_FLIGHT, WINDOW, actor, VisibilityScope.unbounded()));

        // Rejected before the asset is even resolved -- not an attempt, not audited.
        assertTrue(vehicleConfigPort.probedLinkKeys.isEmpty());
        assertTrue(auditTrail.recorded.isEmpty());
    }

    @Test
    void captureSnapshotDeniedWhenAssetIsOutOfScopeAndAuditsTheDenialWithUsageAndPhase() {
        stubDetails(device);
        UsageId usageId = UsageId.random();

        assertThrows(AccessDeniedException.class, () -> service.captureSnapshot(assetId, usageId,
                FlightPhase.POSTFLIGHT, WINDOW, actor, VisibilityScope.groups(Set.of())));

        assertTrue(vehicleConfigPort.probedLinkKeys.isEmpty());
        assertEquals(1, auditTrail.recorded.size());
        AuditEntry entry = auditTrail.recorded.get(0);
        assertEquals("DENIED:out of scope", entry.details().get("result"));
        assertEquals(usageId.value().toString(), entry.details().get("usageId"));
        assertEquals("POSTFLIGHT", entry.details().get("phase"));
    }

    // -- passport (O11, scoped read) --------------------------------------------------

    @Test
    void passportReturnsBothSnapshotsOnceBothPhasesAreCaptured() {
        UsageId usageId = UsageId.random();
        AssetUsage usage = new AssetUsage(usageId, assetId, Instant.parse("2026-08-18T08:00:00Z"), null, null, null, 0);
        stubDetailsWithUsages(new Ownership(actor, GroupId.random()), List.of(usage), device);

        VehicleProfile preflight = completeProfile();
        VehicleProfile postflight = new VehicleProfile("udp://127.0.0.1:14550#7",
                Instant.parse("2026-08-18T08:30:00Z"), 7, "ardupilot", "4.5.7", "quadcopter", 12345L,
                List.of("MAVLINK2"), List.of(), List.of(new ParameterReading("SR2_EXTRA2", 1.0, "REAL32")), 2300L,
                true, null);
        profileRepository.save(device.id(), usageId, FlightPhase.PREFLIGHT, preflight);
        profileRepository.save(device.id(), usageId, FlightPhase.POSTFLIGHT, postflight);

        FlightPassport passport = service.passport(assetId, usageId, VisibilityScope.unbounded());

        assertEquals(new FlightPassport(usageId, assetId, preflight, postflight), passport);
    }

    @Test
    void passportHasNullFieldsWhenNeitherPhaseWasCapturedYet() {
        UsageId usageId = UsageId.random();
        AssetUsage usage = new AssetUsage(usageId, assetId, Instant.parse("2026-08-18T08:00:00Z"), null, null, null, 0);
        stubDetailsWithUsages(new Ownership(actor, GroupId.random()), List.of(usage), device);

        FlightPassport passport = service.passport(assetId, usageId, VisibilityScope.unbounded());

        assertEquals(new FlightPassport(usageId, assetId, null, null), passport);
    }

    @Test
    void passportThrowsNoSuchElementWhenUsageIsUnknown() {
        stubDetailsWithUsages(new Ownership(actor, GroupId.random()), List.of(), device);

        assertThrows(NoSuchElementException.class,
                () -> service.passport(assetId, UsageId.random(), VisibilityScope.unbounded()));
    }

    /**
     * The bug this wave's fix closes: the old membership check went through {@code
     * AssetDetails#recentUsages()}, capped to the 20 most recent -- a passport for the 21st-oldest
     * flight 404'd even though it genuinely belonged to the asset. {@code requireUsageBelongsToAsset}
     * now resolves {@code usageId} via {@code AssetUsageRepositoryPort#findById} directly, which has
     * no such cap; this usage is deliberately absent from the {@code recentUsages()} list {@link
     * #stubDetailsWithUsages} seeds, and present only in {@link #assetUsageRepository} directly, to
     * prove the lookup no longer goes through the capped list at all.
     */
    @Test
    void passportResolvesAUsageOlderThanTheRecentUsagesWindow() {
        stubDetailsWithUsages(new Ownership(actor, GroupId.random()), List.of(), device); // empty "recent" list
        UsageId oldUsageId = UsageId.random();
        AssetUsage oldUsage = new AssetUsage(oldUsageId, assetId, Instant.parse("2020-01-01T00:00:00Z"), null, null,
                null, 0);
        assetUsageRepository.save(oldUsage); // known to the port, but not "recent"

        VehicleProfile preflight = completeProfile();
        profileRepository.save(device.id(), oldUsageId, FlightPhase.PREFLIGHT, preflight);

        FlightPassport passport = service.passport(assetId, oldUsageId, VisibilityScope.unbounded());

        assertEquals(new FlightPassport(oldUsageId, assetId, preflight, null), passport);
    }

    /**
     * The property the fix must not weaken: {@code VehicleProfileRepositoryPort} keys purely by
     * {@code usageId}, not by asset, so a caller scoped to {@code assetId} must not be able to read
     * another asset's passport by guessing/reusing a {@code usageId} that happens to be known to
     * {@code assetUsageRepository}.
     */
    @Test
    void passportThrowsNoSuchElementWhenUsageBelongsToADifferentAsset() {
        stubDetailsWithUsages(new Ownership(actor, GroupId.random()), List.of(), device);
        AssetId otherAssetId = AssetId.random();
        UsageId otherUsageId = UsageId.random();
        AssetUsage otherAssetUsage = new AssetUsage(otherUsageId, otherAssetId,
                Instant.parse("2026-08-18T08:00:00Z"), null, null, null, 0);
        assetUsageRepository.save(otherAssetUsage);
        profileRepository.save(device.id(), otherUsageId, FlightPhase.PREFLIGHT, completeProfile());

        assertThrows(NoSuchElementException.class,
                () -> service.passport(assetId, otherUsageId, VisibilityScope.unbounded()));
    }

    // -- driftFromPreviousFlight (O11, config-drift) -----------------------------------

    /** The exit criterion, verbatim: one changed parameter, one drift row, both values, both timestamps. */
    @Test
    void driftFromPreviousFlightReportsExactlyOneRowForOneChangedParameter() {
        Instant previousStart = Instant.parse("2026-08-18T08:00:00Z");
        Instant currentStart = Instant.parse("2026-08-18T09:00:00Z");
        UsageId previousUsageId = UsageId.random();
        UsageId currentUsageId = UsageId.random();
        AssetUsage previousUsage = new AssetUsage(previousUsageId, assetId, previousStart, null, null, null, 0);
        AssetUsage currentUsage = new AssetUsage(currentUsageId, assetId, currentStart, null, null, null, 0);
        // recentUsages is newest-first.
        stubDetailsWithUsages(new Ownership(actor, GroupId.random()), List.of(currentUsage, previousUsage), device);

        Instant previousObservedAt = previousStart.plusSeconds(1_800);
        Instant currentObservedAt = currentStart.minusSeconds(60);
        VehicleProfile previousPostflight = new VehicleProfile("udp://127.0.0.1:14550#7", previousObservedAt, 7,
                "ardupilot", "4.5.7", "quadcopter", null, List.of(), List.of(),
                List.of(new ParameterReading("SR2_EXTRA2", 0.0, "REAL32"),
                        new ParameterReading("RTL_ALT", 1500.0, "REAL32")),
                null, true, null);
        VehicleProfile currentPreflight = new VehicleProfile("udp://127.0.0.1:14550#7", currentObservedAt, 7,
                "ardupilot", "4.5.7", "quadcopter", null, List.of(), List.of(),
                List.of(new ParameterReading("SR2_EXTRA2", 1.0, "REAL32"),
                        new ParameterReading("RTL_ALT", 1500.0, "REAL32")),
                null, true, null);
        profileRepository.save(device.id(), previousUsageId, FlightPhase.POSTFLIGHT, previousPostflight);
        profileRepository.save(device.id(), currentUsageId, FlightPhase.PREFLIGHT, currentPreflight);

        List<ParameterDrift> drift =
                service.driftFromPreviousFlight(assetId, currentUsageId, VisibilityScope.unbounded());

        assertEquals(List.of(new ParameterDrift("SR2_EXTRA2", 0.0, 1.0, previousObservedAt, currentObservedAt)),
                drift);
    }

    @Test
    void driftFromPreviousFlightIsEmptyWhenThereIsNoPreviousFlight() {
        UsageId usageId = UsageId.random();
        AssetUsage usage = new AssetUsage(usageId, assetId, Instant.parse("2026-08-18T08:00:00Z"), null, null, null, 0);
        stubDetailsWithUsages(new Ownership(actor, GroupId.random()), List.of(usage), device);

        assertEquals(List.of(), service.driftFromPreviousFlight(assetId, usageId, VisibilityScope.unbounded()));
    }

    @Test
    void driftFromPreviousFlightIsEmptyWhenEitherSnapshotWasNeverCaptured() {
        Instant previousStart = Instant.parse("2026-08-18T08:00:00Z");
        Instant currentStart = Instant.parse("2026-08-18T09:00:00Z");
        UsageId previousUsageId = UsageId.random();
        UsageId currentUsageId = UsageId.random();
        AssetUsage previousUsage = new AssetUsage(previousUsageId, assetId, previousStart, null, null, null, 0);
        AssetUsage currentUsage = new AssetUsage(currentUsageId, assetId, currentStart, null, null, null, 0);
        stubDetailsWithUsages(new Ownership(actor, GroupId.random()), List.of(currentUsage, previousUsage), device);
        // Neither the previous flight's POSTFLIGHT nor this flight's PREFLIGHT was ever captured.

        assertEquals(List.of(), service.driftFromPreviousFlight(assetId, currentUsageId, VisibilityScope.unbounded()));
    }

    @Test
    void driftFromPreviousFlightThrowsNoSuchElementWhenUsageIsUnknown() {
        stubDetailsWithUsages(new Ownership(actor, GroupId.random()), List.of(), device);

        assertThrows(NoSuchElementException.class,
                () -> service.driftFromPreviousFlight(assetId, UsageId.random(), VisibilityScope.unbounded()));
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
        private final Map<UsageId, Map<FlightPhase, VehicleProfile>> byUsage = new HashMap<>();

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
            latest.put(deviceId, profile);
            byUsage.computeIfAbsent(usageId, key -> new HashMap<>()).put(phase, profile);
        }

        @Override
        public Optional<VehicleProfile> findByUsageAndPhase(UsageId usageId, FlightPhase phase) {
            return Optional.ofNullable(byUsage.getOrDefault(usageId, Map.of()).get(phase));
        }
    }

    private static final class FakeAssetUsageRepositoryPort implements AssetUsageRepositoryPort {
        private final Map<UsageId, AssetUsage> byId = new HashMap<>();

        @Override
        public AssetUsage save(AssetUsage usage) {
            byId.put(usage.id(), usage);
            return usage;
        }

        @Override
        public Optional<AssetUsage> findById(UsageId id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<AssetUsage> findRecentByAsset(AssetId assetId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AssetUsage> findRecent(int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<AssetUsage> findOpenByAsset(AssetId assetId) {
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

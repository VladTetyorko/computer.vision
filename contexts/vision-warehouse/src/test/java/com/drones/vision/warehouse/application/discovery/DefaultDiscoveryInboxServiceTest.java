package com.drones.vision.warehouse.application.discovery;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.UserId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.Authority;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.application.asset.AssetDetails;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.asset.AssetSpec;
import com.drones.vision.warehouse.application.asset.AssetSummary;
import com.drones.vision.warehouse.application.asset.DuplicateDeviceMatch;
import com.drones.vision.warehouse.application.device.DeviceRegistration;
import com.drones.vision.warehouse.application.device.DeviceService;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.warehouse.domain.model.DiscoveredDevice;
import com.drones.vision.warehouse.domain.model.DiscoveryCandidate;
import com.drones.vision.warehouse.domain.model.CandidateStatus;
import com.drones.vision.warehouse.domain.model.DiscoveryCandidateId;
import com.drones.vision.warehouse.domain.model.Identity;
import com.drones.vision.warehouse.domain.port.DiscoveryCandidateRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultDiscoveryInboxServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(120);

    private DiscoveryCandidateRepositoryPort candidateRepository;
    private AssetService assetService;
    private DeviceService deviceService;
    private final UserId actor = UserId.random();

    @BeforeEach
    void setUp() {
        candidateRepository = mock(DiscoveryCandidateRepositoryPort.class);
        assetService = mock(AssetService.class);
        deviceService = mock(DeviceService.class);
        when(candidateRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private DefaultDiscoveryInboxService serviceAt(Instant now) {
        return new DefaultDiscoveryInboxService(candidateRepository, assetService, deviceService, () -> now);
    }

    private static AssetDetails detailsOf(AssetId assetId) {
        Asset asset = Asset.register(assetId, "Existing asset", new CategoryId("drone"),
                new Ownership(UserId.random(), GroupId.random()), Set.of(), Map.of(), Identity.NONE, Custody.NONE);
        AssetSummary summary = new AssetSummary(asset, "Drone", com.drones.vision.warehouse.application.asset.AssetStatus.OFFLINE,
                null, null, com.drones.vision.warehouse.domain.model.InventoryState.IN_STOCK, Identity.NONE, Custody.NONE);
        return new AssetDetails(summary, java.util.List.of(), java.util.List.of());
    }

    private static DiscoveredDevice onvifCandidate(String host) {
        return new DiscoveredDevice("onvif", "cam-" + host, URI.create("http://" + host + "/onvif"),
                new CategoryId("ip-camera"), null, Map.of());
    }

    private static DiscoveredDevice mavlinkCandidate(int sysid) {
        StreamDescriptor stream = new StreamDescriptor("mavlink", URI.create("udp://0.0.0.0:14550"),
                Map.of("sysid", String.valueOf(sysid)));
        return new DiscoveredDevice("mavlink", "ArduPilot rover (sysid " + sysid + ")",
                URI.create("udp://0.0.0.0:14550"), new CategoryId("drone"), stream,
                Map.of("sysid", String.valueOf(sysid)));
    }

    // -- report: new identity ------------------------------------------------------------------

    @Test
    void reportOfANewIdentityWithNoDuplicateCreatesANewCandidate() {
        DiscoveredDevice discovered = onvifCandidate("10.0.0.1");
        when(candidateRepository.findByIdentityKey(DiscoveryCandidate.identityKeyFor(discovered)))
                .thenReturn(Optional.empty());

        ReportOutcome outcome = serviceAt(NOW).report(discovered);
        DiscoveryCandidate result = outcome.candidate();

        assertEquals(CandidateStatus.NEW, result.status());
        assertEquals(NOW, result.firstSeen());
        assertEquals(NOW, result.lastSeen());
        assertNull(result.registeredAsset());
        assertEquals(discovered, result.discovered());
        assertTrue(outcome.changed(), "a first sighting is always \"changed\"");
        // no suggestedStream on this candidate -- the duplicate check must never even be asked.
        verifyNoInteractions(assetService);
    }

    @Test
    void reportSkipsTheDuplicateCheckWhenDiscoveredHasNoSuggestedStream() {
        DiscoveredDevice discovered = onvifCandidate("10.0.0.9");
        when(candidateRepository.findByIdentityKey(any())).thenReturn(Optional.empty());

        serviceAt(NOW).report(discovered);

        verifyNoInteractions(assetService);
    }

    // -- report: re-report refreshes ------------------------------------------------------------

    @Test
    void reportOfAnAlreadySeenIdentityRefreshesDiscoveredAndLastSeenButKeepsFirstSeenAndStatus() {
        DiscoveredDevice firstSeenDevice = onvifCandidate("10.0.0.2");
        DiscoveryCandidate existing = DiscoveryCandidate.newlyReported(DiscoveryCandidateId.random(),
                firstSeenDevice, NOW);
        when(candidateRepository.findByIdentityKey(existing.identityKey())).thenReturn(Optional.of(existing));

        DiscoveredDevice reAnnounced = new DiscoveredDevice("onvif", "cam-10.0.0.2-renamed",
                firstSeenDevice.address(), firstSeenDevice.suggestedCategory(), null, Map.of("extra", "1"));

        ReportOutcome outcome = serviceAt(LATER).report(reAnnounced);
        DiscoveryCandidate result = outcome.candidate();

        assertEquals(reAnnounced, result.discovered());
        assertEquals(NOW, result.firstSeen(), "firstSeen must not move on a re-report");
        assertEquals(LATER, result.lastSeen());
        assertEquals(CandidateStatus.NEW, result.status());
        assertEquals(existing.id(), result.id());
        assertTrue(outcome.changed(), "discovered() differs from the pre-report value (renamed + extra detail)");
    }

    @Test
    void reportOfAnUnchangedReAnnouncementIsNotReportedAsChanged() {
        DiscoveredDevice discovered = onvifCandidate("10.0.0.20");
        DiscoveryCandidate existing = DiscoveryCandidate.newlyReported(DiscoveryCandidateId.random(), discovered, NOW);
        when(candidateRepository.findByIdentityKey(existing.identityKey())).thenReturn(Optional.of(existing));

        ReportOutcome outcome = serviceAt(LATER).report(discovered);

        assertEquals(CandidateStatus.NEW, outcome.candidate().status());
        assertEquals(discovered, outcome.candidate().discovered());
        assertFalse(outcome.changed(),
                "a routine re-report that only refreshes lastSeen must not be reported as changed");
    }

    @Test
    void reportKeepsADismissedCandidateDismissedOnReReportWithNoDuplicateMatch() {
        DiscoveredDevice discovered = onvifCandidate("10.0.0.3");
        DiscoveryCandidate dismissed =
                DiscoveryCandidate.newlyReported(DiscoveryCandidateId.random(), discovered, NOW).dismiss();
        when(candidateRepository.findByIdentityKey(dismissed.identityKey())).thenReturn(Optional.of(dismissed));

        ReportOutcome outcome = serviceAt(LATER).report(discovered);
        DiscoveryCandidate result = outcome.candidate();

        assertEquals(CandidateStatus.DISMISSED, result.status(), "the operator's dismissal must outlive the sweep");
        assertNull(result.registeredAsset());
        assertFalse(outcome.changed(), "status and discovered are unchanged from the pre-report value");
    }

    // -- report: already-registered device wins ---------------------------------------------

    @Test
    void reportOfACandidateMatchingARegisteredDeviceIsRecordedRegisteredNeverNew() {
        DiscoveredDevice discovered = mavlinkCandidate(7);
        AssetId owner = AssetId.random();
        when(candidateRepository.findByIdentityKey(any())).thenReturn(Optional.empty());
        when(assetService.findDuplicateDevice(discovered.suggestedStream()))
                .thenReturn(Optional.of(new DuplicateDeviceMatch(com.drones.vision.kernel.DeviceId.random(), owner)));

        DiscoveryCandidate result = serviceAt(NOW).report(discovered).candidate();

        assertEquals(CandidateStatus.REGISTERED, result.status());
        assertEquals(owner, result.registeredAsset());
    }

    @Test
    void reportOfACandidateMatchingAnUnownedRegisteredDeviceIsRegisteredWithNoAsset() {
        DiscoveredDevice discovered = mavlinkCandidate(9);
        when(candidateRepository.findByIdentityKey(any())).thenReturn(Optional.empty());
        when(assetService.findDuplicateDevice(discovered.suggestedStream()))
                .thenReturn(Optional.of(new DuplicateDeviceMatch(com.drones.vision.kernel.DeviceId.random(), null)));

        DiscoveryCandidate result = serviceAt(NOW).report(discovered).candidate();

        assertEquals(CandidateStatus.REGISTERED, result.status());
        assertNull(result.registeredAsset());
    }

    @Test
    void reportOverridesADismissedCandidateToRegisteredWhenItNowMatchesARegisteredDevice() {
        DiscoveredDevice discovered = mavlinkCandidate(4);
        DiscoveryCandidate dismissed =
                DiscoveryCandidate.newlyReported(DiscoveryCandidateId.random(), discovered, NOW).dismiss();
        AssetId owner = AssetId.random();
        when(candidateRepository.findByIdentityKey(dismissed.identityKey())).thenReturn(Optional.of(dismissed));
        when(assetService.findDuplicateDevice(discovered.suggestedStream()))
                .thenReturn(Optional.of(new DuplicateDeviceMatch(com.drones.vision.kernel.DeviceId.random(), owner)));

        ReportOutcome outcome = serviceAt(LATER).report(discovered);

        assertEquals(CandidateStatus.REGISTERED, outcome.candidate().status(),
                "an objective already-registered fact must win over a stale dismissal");
        assertEquals(owner, outcome.candidate().registeredAsset());
        assertTrue(outcome.changed(), "status moved from DISMISSED to REGISTERED");
    }

    // -- report: C5 auto-reopen (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md &sect;3.2 C5) ------

    @Test
    void reportReopensARegisteredCandidateToNewWhenItsMatchIsGone() {
        DiscoveredDevice discovered = mavlinkCandidate(11);
        AssetId formerOwner = AssetId.random();
        DiscoveryCandidate registered = DiscoveryCandidate.newlyReported(DiscoveryCandidateId.random(), discovered, NOW)
                .registeredTo(formerOwner);
        when(candidateRepository.findByIdentityKey(registered.identityKey())).thenReturn(Optional.of(registered));
        // The asset/device this candidate matched was since deleted -- no active device carries the
        // identity any more.
        when(assetService.findDuplicateDevice(discovered.suggestedStream())).thenReturn(Optional.empty());

        ReportOutcome outcome = serviceAt(LATER).report(discovered);

        assertEquals(CandidateStatus.NEW, outcome.candidate().status(),
                "a REGISTERED candidate whose match disappeared must reopen to NEW");
        assertNull(outcome.candidate().registeredAsset());
        assertTrue(outcome.changed(), "status moved from REGISTERED to NEW");
    }

    @Test
    void reportDoesNotAutoReopenADismissedCandidateEvenWithNoDuplicateMatch() {
        // Auto-reopen is specific to REGISTERED; a dismissed candidate stays dismissed regardless.
        DiscoveredDevice discovered = mavlinkCandidate(12);
        DiscoveryCandidate dismissed =
                DiscoveryCandidate.newlyReported(DiscoveryCandidateId.random(), discovered, NOW).dismiss();
        when(candidateRepository.findByIdentityKey(dismissed.identityKey())).thenReturn(Optional.of(dismissed));
        when(assetService.findDuplicateDevice(discovered.suggestedStream())).thenReturn(Optional.empty());

        DiscoveryCandidate result = serviceAt(LATER).report(discovered).candidate();

        assertEquals(CandidateStatus.DISMISSED, result.status());
    }

    // -- candidates() -----------------------------------------------------------------------------

    @Test
    void candidatesReturnsWhateverTheRepositoryHolds() {
        DiscoveryCandidate one =
                DiscoveryCandidate.newlyReported(DiscoveryCandidateId.random(), onvifCandidate("10.0.0.4"), NOW);
        when(candidateRepository.findAll()).thenReturn(java.util.List.of(one));

        assertEquals(java.util.List.of(one), serviceAt(NOW).candidates());
    }

    // -- dismiss --------------------------------------------------------------------------------

    @Test
    void dismissMarksAnExistingCandidateDismissed() {
        DiscoveryCandidate candidate =
                DiscoveryCandidate.newlyReported(DiscoveryCandidateId.random(), onvifCandidate("10.0.0.5"), NOW);
        when(candidateRepository.findById(candidate.id())).thenReturn(Optional.of(candidate));

        DiscoveryCandidate result = serviceAt(NOW).dismiss(candidate.id(), actor);

        assertEquals(CandidateStatus.DISMISSED, result.status());
    }

    @Test
    void dismissThrowsForUnknownId() {
        DiscoveryCandidateId unknown = DiscoveryCandidateId.random();
        when(candidateRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> serviceAt(NOW).dismiss(unknown, actor));
    }

    // -- register ---------------------------------------------------------------------------------

    private RegisterFromCandidateCommand commandFor(GroupId groupId) {
        return new RegisterFromCandidateCommand("My rover", new CategoryId("drone"), Map.of("color", "red"),
                new Identity("SN-1", "ArduPilot", "rover", null), new Ownership(actor, groupId));
    }

    @Test
    void registerBuildsAnAssetSpecFromTheCandidateAndDelegatesToCreateFromCandidate() {
        DiscoveredDevice discovered = mavlinkCandidate(5);
        DiscoveryCandidate candidate = DiscoveryCandidate.newlyReported(DiscoveryCandidateId.random(), discovered, NOW);
        when(candidateRepository.findById(candidate.id())).thenReturn(Optional.of(candidate));
        GroupId group = GroupId.random();
        RegisterFromCandidateCommand command = commandFor(group);
        Asset created = Asset.register(AssetId.random(), command.displayName(), command.category(),
                command.ownership(), Set.of(), Map.of(), Identity.NONE, Custody.NONE);
        ArgumentCaptor<AssetSpec> specCaptor = ArgumentCaptor.forClass(AssetSpec.class);
        when(assetService.createFromCandidate(specCaptor.capture(), any(), any())).thenReturn(created);

        Asset result = serviceAt(NOW).register(candidate.id(), command, Authority.full(), actor);

        assertEquals(created, result);
        AssetSpec spec = specCaptor.getValue();
        assertEquals("My rover", spec.displayName());
        assertEquals(new CategoryId("drone"), spec.category());
        assertEquals(Map.of("color", "red"), spec.attributes());
        assertEquals(1, spec.devices().size());
        assertEquals(discovered.suggestedStream(), spec.devices().get(0).stream());
        assertEquals(Set.of(Capability.TELEMETRY), spec.devices().get(0).capabilities(),
                "a mavlink candidate's device must default to telemetry-only capability");
        assertEquals(command.identity(), spec.identity());

        verify(assetService).createFromCandidate(any(), org.mockito.ArgumentMatchers.eq(command.ownership()),
                org.mockito.ArgumentMatchers.eq(actor));
        ArgumentCaptor<DiscoveryCandidate> savedCaptor = ArgumentCaptor.forClass(DiscoveryCandidate.class);
        verify(candidateRepository).save(savedCaptor.capture());
        assertEquals(CandidateStatus.REGISTERED, savedCaptor.getValue().status());
        assertEquals(created.id(), savedCaptor.getValue().registeredAsset());
    }

    @Test
    void registerDefaultsToVideoCapabilityForANonMavlinkCandidate() {
        DiscoveredDevice discovered = new DiscoveredDevice("mdns", "cam-1", URI.create("rtsp://10.0.0.6/stream"),
                new CategoryId("ip-camera"),
                new StreamDescriptor("rtsp", URI.create("rtsp://10.0.0.6/stream"), Map.of()), Map.of());
        DiscoveryCandidate candidate = DiscoveryCandidate.newlyReported(DiscoveryCandidateId.random(), discovered, NOW);
        when(candidateRepository.findById(candidate.id())).thenReturn(Optional.of(candidate));
        RegisterFromCandidateCommand command = commandFor(GroupId.random());
        ArgumentCaptor<AssetSpec> specCaptor = ArgumentCaptor.forClass(AssetSpec.class);
        when(assetService.createFromCandidate(specCaptor.capture(), any(), any()))
                .thenReturn(Asset.register(AssetId.random(), "x", new CategoryId("drone"), command.ownership(),
                        Set.of(), Map.of(), Identity.NONE, Custody.NONE));

        serviceAt(NOW).register(candidate.id(), command, Authority.full(), actor);

        assertEquals(Set.of(Capability.VIDEO), specCaptor.getValue().devices().get(0).capabilities());
    }

    @Test
    void registerPropagatesTheDuplicateExceptionWithoutStampingTheCandidate() {
        DiscoveredDevice discovered = mavlinkCandidate(6);
        DiscoveryCandidate candidate = DiscoveryCandidate.newlyReported(DiscoveryCandidateId.random(), discovered, NOW);
        when(candidateRepository.findById(candidate.id())).thenReturn(Optional.of(candidate));
        RegisterFromCandidateCommand command = commandFor(GroupId.random());
        when(assetService.createFromCandidate(any(), any(), any()))
                .thenThrow(new IllegalStateException("Candidate mavlink udp://x is already registered to asset y"));

        assertThrows(IllegalStateException.class,
                () -> serviceAt(NOW).register(candidate.id(), command, Authority.full(), actor));

        verify(candidateRepository, never()).save(any());
    }

    @Test
    void registerRefusesWhenScopeCannotManageOrg() {
        RegisterFromCandidateCommand command = commandFor(GroupId.random());
        Authority pilotScope = new Authority(VisibilityScope.assignedAssets(Set.of()),
                Set.of(com.drones.vision.platform.Capability.MANAGE_ORG));

        assertThrows(AccessDeniedException.class,
                () -> serviceAt(NOW).register(DiscoveryCandidateId.random(), command, pilotScope, actor));

        verifyNoInteractions(assetService);
        verify(candidateRepository, never()).findById(any());
    }

    @Test
    void registerRefusesWhenTheCommandsOwnershipGroupIsOutsideScope() {
        GroupId inScopeGroup = GroupId.random();
        GroupId targetGroup = GroupId.random();
        RegisterFromCandidateCommand command = commandFor(targetGroup);
        Authority scope = new Authority(VisibilityScope.groups(Set.of(inScopeGroup)),
                Set.of(com.drones.vision.platform.Capability.MANAGE_ORG));

        assertThrows(AccessDeniedException.class,
                () -> serviceAt(NOW).register(DiscoveryCandidateId.random(), command, scope, actor));

        verifyNoInteractions(assetService);
    }

    @Test
    void registerThrowsForUnknownCandidateId() {
        RegisterFromCandidateCommand command = commandFor(GroupId.random());
        DiscoveryCandidateId unknown = DiscoveryCandidateId.random();
        when(candidateRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> serviceAt(NOW).register(unknown, command, Authority.full(), actor));
    }

    // -- attach (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md &sect;3.2 C1) -----------------------

    private DiscoveredDevice mavlinkCandidateWithStream(int sysid) {
        return mavlinkCandidate(sysid);
    }

    @Test
    void attachRegistersADeviceFromTheCandidateAndAssignsItToTheAsset() {
        DiscoveredDevice discovered = mavlinkCandidateWithStream(21);
        DiscoveryCandidate candidate = DiscoveryCandidate.newlyReported(DiscoveryCandidateId.random(), discovered, NOW);
        AssetId assetId = AssetId.random();
        when(candidateRepository.findById(candidate.id())).thenReturn(Optional.of(candidate));
        when(assetService.details(VisibilityScope.unbounded(), assetId)).thenReturn(detailsOf(assetId));
        when(assetService.findDuplicateDevice(discovered.suggestedStream())).thenReturn(Optional.empty());
        Device registeredDevice = new Device(DeviceId.random(), discovered.name(), Set.of(Capability.TELEMETRY),
                discovered.suggestedStream());
        when(deviceService.register(any(), any())).thenReturn(registeredDevice);

        DiscoveryCandidate result = serviceAt(NOW).attach(candidate.id(), assetId, VisibilityScope.unbounded(), actor);

        assertEquals(CandidateStatus.REGISTERED, result.status());
        assertEquals(assetId, result.registeredAsset());
        ArgumentCaptor<DeviceRegistration> registrationCaptor = ArgumentCaptor.forClass(DeviceRegistration.class);
        verify(deviceService).register(registrationCaptor.capture(), org.mockito.ArgumentMatchers.eq(actor));
        assertEquals(discovered.suggestedStream(), registrationCaptor.getValue().stream());
        assertEquals(Set.of(Capability.TELEMETRY), registrationCaptor.getValue().capabilities());
        verify(assetService).assignDevice(assetId, registeredDevice.id(), actor);
    }

    @Test
    void attachRefusesWhenScopeCannotManageOrg() {
        VisibilityScope pilotScope = VisibilityScope.assignedAssets(Set.of());

        assertThrows(AccessDeniedException.class,
                () -> serviceAt(NOW).attach(DiscoveryCandidateId.random(), AssetId.random(), pilotScope, actor));

        verifyNoInteractions(assetService, deviceService);
    }

    @Test
    void attachThrowsForUnknownCandidateId() {
        DiscoveryCandidateId unknown = DiscoveryCandidateId.random();
        when(candidateRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> serviceAt(NOW).attach(unknown, AssetId.random(), VisibilityScope.unbounded(), actor));
    }

    @Test
    void attachThrowsNotFoundNeverForbiddenForAnAssetOutsideScope() {
        DiscoveredDevice discovered = mavlinkCandidateWithStream(22);
        DiscoveryCandidate candidate = DiscoveryCandidate.newlyReported(DiscoveryCandidateId.random(), discovered, NOW);
        AssetId assetId = AssetId.random();
        when(candidateRepository.findById(candidate.id())).thenReturn(Optional.of(candidate));
        when(assetService.details(VisibilityScope.unbounded(), assetId))
                .thenThrow(new NoSuchElementException("Unknown asset: " + assetId.value()));

        assertThrows(NoSuchElementException.class,
                () -> serviceAt(NOW).attach(candidate.id(), assetId, VisibilityScope.unbounded(), actor),
                "an out-of-scope/unknown target asset must be 404, never 403 (repo convention)");
        verify(deviceService, never()).register(any(), any());
    }

    @Test
    void attachThrowsConflictWhenTheCandidateHasNoSuggestedStream() {
        DiscoveredDevice discovered = onvifCandidate("10.0.0.30"); // no suggestedStream
        DiscoveryCandidate candidate = DiscoveryCandidate.newlyReported(DiscoveryCandidateId.random(), discovered, NOW);
        AssetId assetId = AssetId.random();
        when(candidateRepository.findById(candidate.id())).thenReturn(Optional.of(candidate));

        assertThrows(IllegalStateException.class,
                () -> serviceAt(NOW).attach(candidate.id(), assetId, VisibilityScope.unbounded(), actor));
        verifyNoInteractions(deviceService);
    }

    @Test
    void attachThrowsConflictWhenTheStreamDuplicatesARegisteredDevice() {
        DiscoveredDevice discovered = mavlinkCandidateWithStream(23);
        DiscoveryCandidate candidate = DiscoveryCandidate.newlyReported(DiscoveryCandidateId.random(), discovered, NOW);
        AssetId assetId = AssetId.random();
        AssetId owningAsset = AssetId.random();
        when(candidateRepository.findById(candidate.id())).thenReturn(Optional.of(candidate));
        when(assetService.details(VisibilityScope.unbounded(), assetId)).thenReturn(detailsOf(assetId));
        when(assetService.findDuplicateDevice(discovered.suggestedStream()))
                .thenReturn(Optional.of(new DuplicateDeviceMatch(DeviceId.random(), owningAsset)));
        when(assetService.details(owningAsset)).thenReturn(detailsOf(owningAsset));

        assertThrows(IllegalStateException.class,
                () -> serviceAt(NOW).attach(candidate.id(), assetId, VisibilityScope.unbounded(), actor));
        verify(deviceService, never()).register(any(), any());
    }

    @Test
    void attachThrowsAlreadyRegisteredWhenTheCandidateIsRegisteredToADifferentAsset() {
        DiscoveredDevice discovered = mavlinkCandidateWithStream(24);
        AssetId otherAsset = AssetId.random();
        DiscoveryCandidate candidate = DiscoveryCandidate.newlyReported(DiscoveryCandidateId.random(), discovered, NOW)
                .registeredTo(otherAsset);
        AssetId targetAsset = AssetId.random();
        when(candidateRepository.findById(candidate.id())).thenReturn(Optional.of(candidate));

        assertThrows(DiscoveryCandidateAlreadyRegisteredException.class,
                () -> serviceAt(NOW).attach(candidate.id(), targetAsset, VisibilityScope.unbounded(), actor));
        verifyNoInteractions(deviceService);
    }

    // -- restore --------------------------------------------------------------------------------

    @Test
    void restoreReopensACandidateToNewAndClearsItsRegisteredAsset() {
        DiscoveredDevice discovered = onvifCandidate("10.0.0.40");
        DiscoveryCandidate registered = DiscoveryCandidate.newlyReported(DiscoveryCandidateId.random(), discovered, NOW)
                .registeredTo(AssetId.random());
        when(candidateRepository.findById(registered.id())).thenReturn(Optional.of(registered));

        DiscoveryCandidate result = serviceAt(NOW).restore(registered.id(), actor);

        assertEquals(CandidateStatus.NEW, result.status());
        assertNull(result.registeredAsset());
    }

    @Test
    void restoreThrowsForUnknownId() {
        DiscoveryCandidateId unknown = DiscoveryCandidateId.random();
        when(candidateRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> serviceAt(NOW).restore(unknown, actor));
    }

    // -- constructor ------------------------------------------------------------------------------

    @Test
    void constructorRejectsNullArguments() {
        assertThrows(NullPointerException.class,
                () -> new DefaultDiscoveryInboxService(null, assetService, deviceService));
        assertThrows(NullPointerException.class,
                () -> new DefaultDiscoveryInboxService(candidateRepository, null, deviceService));
        assertThrows(NullPointerException.class,
                () -> new DefaultDiscoveryInboxService(candidateRepository, assetService, null));
    }
}

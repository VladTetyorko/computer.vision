package com.drones.vision.warehouse.application.discovery;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.Authority;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.asset.AssetSpec;
import com.drones.vision.warehouse.application.asset.DuplicateDeviceMatch;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.Custody;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private final UserId actor = UserId.random();

    @BeforeEach
    void setUp() {
        candidateRepository = mock(DiscoveryCandidateRepositoryPort.class);
        assetService = mock(AssetService.class);
        when(candidateRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private DefaultDiscoveryInboxService serviceAt(Instant now) {
        return new DefaultDiscoveryInboxService(candidateRepository, assetService, () -> now);
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

        DiscoveryCandidate result = serviceAt(NOW).report(discovered);

        assertEquals(CandidateStatus.NEW, result.status());
        assertEquals(NOW, result.firstSeen());
        assertEquals(NOW, result.lastSeen());
        assertNull(result.registeredAsset());
        assertEquals(discovered, result.discovered());
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

        DiscoveryCandidate result = serviceAt(LATER).report(reAnnounced);

        assertEquals(reAnnounced, result.discovered());
        assertEquals(NOW, result.firstSeen(), "firstSeen must not move on a re-report");
        assertEquals(LATER, result.lastSeen());
        assertEquals(CandidateStatus.NEW, result.status());
        assertEquals(existing.id(), result.id());
    }

    @Test
    void reportKeepsADismissedCandidateDismissedOnReReportWithNoDuplicateMatch() {
        DiscoveredDevice discovered = onvifCandidate("10.0.0.3");
        DiscoveryCandidate dismissed =
                DiscoveryCandidate.newlyReported(DiscoveryCandidateId.random(), discovered, NOW).dismiss();
        when(candidateRepository.findByIdentityKey(dismissed.identityKey())).thenReturn(Optional.of(dismissed));

        DiscoveryCandidate result = serviceAt(LATER).report(discovered);

        assertEquals(CandidateStatus.DISMISSED, result.status(), "the operator's dismissal must outlive the sweep");
        assertNull(result.registeredAsset());
    }

    // -- report: already-registered device wins ---------------------------------------------

    @Test
    void reportOfACandidateMatchingARegisteredDeviceIsRecordedRegisteredNeverNew() {
        DiscoveredDevice discovered = mavlinkCandidate(7);
        AssetId owner = AssetId.random();
        when(candidateRepository.findByIdentityKey(any())).thenReturn(Optional.empty());
        when(assetService.findDuplicateDevice(discovered.suggestedStream()))
                .thenReturn(Optional.of(new DuplicateDeviceMatch(com.drones.vision.kernel.DeviceId.random(), owner)));

        DiscoveryCandidate result = serviceAt(NOW).report(discovered);

        assertEquals(CandidateStatus.REGISTERED, result.status());
        assertEquals(owner, result.registeredAsset());
    }

    @Test
    void reportOfACandidateMatchingAnUnownedRegisteredDeviceIsRegisteredWithNoAsset() {
        DiscoveredDevice discovered = mavlinkCandidate(9);
        when(candidateRepository.findByIdentityKey(any())).thenReturn(Optional.empty());
        when(assetService.findDuplicateDevice(discovered.suggestedStream()))
                .thenReturn(Optional.of(new DuplicateDeviceMatch(com.drones.vision.kernel.DeviceId.random(), null)));

        DiscoveryCandidate result = serviceAt(NOW).report(discovered);

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

        DiscoveryCandidate result = serviceAt(LATER).report(discovered);

        assertEquals(CandidateStatus.REGISTERED, result.status(),
                "an objective already-registered fact must win over a stale dismissal");
        assertEquals(owner, result.registeredAsset());
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

    // -- constructor ------------------------------------------------------------------------------

    @Test
    void constructorRejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> new DefaultDiscoveryInboxService(null, assetService));
        assertThrows(NullPointerException.class, () -> new DefaultDiscoveryInboxService(candidateRepository, null));
    }
}

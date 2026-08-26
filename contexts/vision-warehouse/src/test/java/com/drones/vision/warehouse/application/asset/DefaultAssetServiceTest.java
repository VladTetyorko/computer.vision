package com.drones.vision.warehouse.application.asset;

import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.warehouse.domain.model.AssetUsage;
import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.warehouse.domain.model.DeviceCategory;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.LifecycleState;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.kernel.UsageOrigin;
import com.drones.vision.warehouse.domain.model.UsagePhase;
import com.drones.vision.kernel.UserId;
import com.drones.vision.warehouse.domain.port.AssetLiveStatePort;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.warehouse.domain.port.CategoryRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.drones.vision.warehouse.application.device.DeviceRegistration;
import com.drones.vision.warehouse.application.device.DeviceService;
import com.drones.vision.platform.VisibilityScope;

class DefaultAssetServiceTest {

    private static final CategoryId DRONE = new CategoryId("drone");

    private AssetRepositoryPort assetRepository;
    private CategoryRepositoryPort categoryRepository;
    private AssetUsageRepositoryPort usageRepository;
    private AuditTrailPort auditTrail;
    private DeviceService deviceService;
    private AssetLiveStatePort assetLiveStatePort;
    private Ownership ownership;
    private UserId actingUser;
    private AssetService service;

    @BeforeEach
    void setUp() {
        assetRepository = mock(AssetRepositoryPort.class);
        categoryRepository = mock(CategoryRepositoryPort.class);
        usageRepository = mock(AssetUsageRepositoryPort.class);
        auditTrail = mock(AuditTrailPort.class);
        deviceService = mock(DeviceService.class);
        assetLiveStatePort = mock(AssetLiveStatePort.class);
        actingUser = UserId.random();
        ownership = new Ownership(actingUser, GroupId.random());

        service = new DefaultAssetService(assetRepository, categoryRepository, usageRepository, auditTrail,
                deviceService, assetLiveStatePort);

        when(assetRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(categoryRepository.findById(DRONE))
                .thenReturn(Optional.of(new DeviceCategory(DRONE, "Drone", null, List.of())));
        when(assetLiveStatePort.activeStreamsByDevice()).thenReturn(Map.of());
        when(assetLiveStatePort.stopStreamsForDevices(any())).thenReturn(0);
        when(usageRepository.findRecentByAsset(any(), anyInt())).thenReturn(List.of());
    }

    // --- Creating ------------------------------------------------------------

    @Test
    void createThrowsForUnknownCategoryAndNeverRegistersDevices() {
        CategoryId unknown = new CategoryId("unknown-category");
        when(categoryRepository.findById(unknown)).thenReturn(Optional.empty());
        AssetSpec spec = new AssetSpec("my drone", unknown, Map.of(), List.of(registration("cam-1")));

        assertThrows(IllegalArgumentException.class, () -> service.create(spec, ownership, actingUser));
        verifyNoInteractions(deviceService);
    }

    @Test
    void assetSpecRejectsZeroDevicesAtTheDomainLevel() {
        assertThrows(IllegalArgumentException.class,
                () -> new AssetSpec("my drone", DRONE, Map.of(), List.of()));
    }

    @Test
    void createRegistersDevicesAndPersistsAssetWithActingOwnership() {
        Device cam = device("cam-1");
        when(deviceService.register(any(), eq(actingUser))).thenReturn(cam);
        AssetSpec spec = new AssetSpec("my drone", DRONE, Map.of("color", "red"),
                List.of(registration("cam-1")));

        Asset created = service.create(spec, ownership, actingUser);

        assertEquals("my drone", created.displayName());
        assertEquals(DRONE, created.category());
        assertEquals(ownership, created.ownership());
        assertEquals(Set.of(cam.id()), created.devices());
        assertEquals(Map.of("color", "red"), created.attributes());

        ArgumentCaptor<Asset> captor = ArgumentCaptor.forClass(Asset.class);
        verify(assetRepository).save(captor.capture());
        assertEquals(created, captor.getValue());
    }

    @Test
    void createRegistersEveryDeviceInTheSpec() {
        Device cam = device("cam-1");
        Device telemetry = device("telemetry-1");
        when(deviceService.register(any(), eq(actingUser))).thenReturn(cam, telemetry);
        AssetSpec spec = new AssetSpec("my drone", DRONE, Map.of(),
                List.of(registration("cam-1"), registration("telemetry-1")));

        Asset created = service.create(spec, ownership, actingUser);

        assertEquals(Set.of(cam.id(), telemetry.id()), created.devices());
    }

    // --- Creating with existingDeviceIds ("promote to asset") ------------------

    @Test
    void createAssignsExistingUnownedDevicesInsteadOfRegisteringNewOnes() {
        Device existing = device("already-registered");
        when(deviceService.find(existing.id())).thenReturn(Optional.of(existing));
        when(assetRepository.findByDeviceId(existing.id())).thenReturn(Optional.empty());
        AssetSpec spec = new AssetSpec("my drone", DRONE, Map.of(), List.of(), List.of(existing.id()));

        Asset created = service.create(spec, ownership, actingUser);

        assertEquals(Set.of(existing.id()), created.devices());
        verify(deviceService, never()).register(any(), any());
    }

    @Test
    void createCombinesNewlyRegisteredAndExistingDevices() {
        Device newCam = device("brand-new");
        when(deviceService.register(any(), eq(actingUser))).thenReturn(newCam);
        Device existing = device("already-registered");
        when(deviceService.find(existing.id())).thenReturn(Optional.of(existing));
        when(assetRepository.findByDeviceId(existing.id())).thenReturn(Optional.empty());
        AssetSpec spec = new AssetSpec("my drone", DRONE, Map.of(), List.of(registration("brand-new")),
                List.of(existing.id()));

        Asset created = service.create(spec, ownership, actingUser);

        assertEquals(Set.of(newCam.id(), existing.id()), created.devices());
    }

    @Test
    void createThrowsForAnUnknownExistingDeviceId() {
        DeviceId unknown = DeviceId.random();
        when(deviceService.find(unknown)).thenReturn(Optional.empty());
        AssetSpec spec = new AssetSpec("my drone", DRONE, Map.of(), List.of(), List.of(unknown));

        assertThrows(NoSuchElementException.class, () -> service.create(spec, ownership, actingUser));
        verify(assetRepository, never()).save(any());
    }

    @Test
    void createRejectsADeletedExistingDeviceId() {
        Device deleted = device("gone").withState(LifecycleState.DELETED);
        when(deviceService.find(deleted.id())).thenReturn(Optional.of(deleted));
        AssetSpec spec = new AssetSpec("my drone", DRONE, Map.of(), List.of(), List.of(deleted.id()));

        assertThrows(IllegalArgumentException.class, () -> service.create(spec, ownership, actingUser));
        verify(assetRepository, never()).save(any());
    }

    @Test
    void createRefusesAnExistingDeviceIdAlreadyOwnedByAnotherAsset() {
        Device owned = device("owned-elsewhere");
        Asset other = asset(Set.of(DeviceId.random()));
        when(deviceService.find(owned.id())).thenReturn(Optional.of(owned));
        when(assetRepository.findByDeviceId(owned.id())).thenReturn(Optional.of(other));
        AssetSpec spec = new AssetSpec("my drone", DRONE, Map.of(), List.of(), List.of(owned.id()));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.create(spec, ownership, actingUser));

        assertTrue(thrown.getMessage().contains(other.displayName()));
        verify(assetRepository, never()).save(any());
    }

    // --- Creating from a discovered candidate (docs/plans/active/DRONE-ONBOARDING-PLAN.md §3.1
    // stage 7, REGISTER; Wave O7) ------------------------------------------------------------

    @Test
    void createFromCandidateBehavesExactlyLikeCreateWhenNothingDuplicates() {
        Device cam = device("cam-1");
        when(deviceService.devices(false)).thenReturn(List.of());
        when(deviceService.register(any(), eq(actingUser))).thenReturn(cam);
        AssetSpec spec = new AssetSpec("my drone", DRONE, Map.of(), List.of(registration("cam-1")));

        Asset created = service.createFromCandidate(spec, ownership, actingUser);

        assertEquals("my drone", created.displayName());
        assertEquals(Set.of(cam.id()), created.devices());
    }

    @Test
    void createFromCandidateThrowsForUnknownCategoryAndNeverChecksDuplicatesOrRegistersDevices() {
        CategoryId unknown = new CategoryId("unknown-category");
        when(categoryRepository.findById(unknown)).thenReturn(Optional.empty());
        AssetSpec spec = new AssetSpec("my drone", unknown, Map.of(), List.of(registration("cam-1")));

        assertThrows(IllegalArgumentException.class,
                () -> service.createFromCandidate(spec, ownership, actingUser));
        verifyNoInteractions(deviceService);
    }

    @Test
    void createFromCandidateRefusesADeviceMatchingAnExistingRegisteredAirframe() {
        Device existing = device("already-out-there");
        Asset owner = asset(Set.of(existing.id()));
        when(deviceService.devices(false)).thenReturn(List.of(existing));
        when(assetRepository.findByDeviceId(existing.id())).thenReturn(Optional.of(owner));
        AssetSpec spec =
                new AssetSpec("my drone", DRONE, Map.of(), List.of(registration("already-out-there")));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.createFromCandidate(spec, ownership, actingUser));

        assertTrue(thrown.getMessage().contains(owner.displayName()));
        verify(assetRepository, never()).save(any());
        verify(deviceService, never()).register(any(), any());
    }

    @Test
    void createFromCandidateNamesTheDeviceWhenTheDuplicateIsRegisteredButNotYetOwnedByAnAsset() {
        Device existing = device("unowned-but-registered");
        when(deviceService.devices(false)).thenReturn(List.of(existing));
        when(assetRepository.findByDeviceId(existing.id())).thenReturn(Optional.empty());
        AssetSpec spec =
                new AssetSpec("my drone", DRONE, Map.of(), List.of(registration("unowned-but-registered")));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.createFromCandidate(spec, ownership, actingUser));

        assertTrue(thrown.getMessage().contains("unowned-but-registered"));
        verify(assetRepository, never()).save(any());
    }

    @Test
    void createFromCandidateDuplicateCheckIsKeyedByProtocolUriAndSysidNotJustUri() {
        StreamDescriptor existingStream =
                new StreamDescriptor("mavlink", URI.create("mavlink://same-uri"), Map.of("sysid", "1"));
        Device existing = new Device(DeviceId.random(), "existing", Set.of(Capability.TELEMETRY), existingStream);
        when(deviceService.devices(false)).thenReturn(List.of(existing));
        Device candidate = device("candidate");
        when(deviceService.register(any(), eq(actingUser))).thenReturn(candidate);
        DeviceRegistration differentSysid = new DeviceRegistration("candidate", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("mavlink", URI.create("mavlink://same-uri"), Map.of("sysid", "2")));
        AssetSpec spec = new AssetSpec("my drone", DRONE, Map.of(), List.of(differentSysid));

        Asset created = service.createFromCandidate(spec, ownership, actingUser);

        assertEquals(Set.of(candidate.id()), created.devices());
    }

    @Test
    void createFromCandidateDuplicateCheckIgnoresSoftDeletedDevices() {
        Device deleted = device("retired").withState(LifecycleState.DELETED);
        when(deviceService.devices(false)).thenReturn(List.of()); // deleted devices excluded by the port contract
        Device candidate = device("retired");
        when(deviceService.register(any(), eq(actingUser))).thenReturn(candidate);
        AssetSpec spec = new AssetSpec("my drone", DRONE, Map.of(), List.of(registration("retired")));

        Asset created = service.createFromCandidate(spec, ownership, actingUser);

        assertEquals(Set.of(candidate.id()), created.devices());
    }

    @Test
    void createFromCandidateStillHonorsExistingDeviceIdsPromoteFlowWithoutDuplicateChecking() {
        Device existing = device("already-registered");
        when(deviceService.find(existing.id())).thenReturn(Optional.of(existing));
        when(assetRepository.findByDeviceId(existing.id())).thenReturn(Optional.empty());
        when(deviceService.devices(false)).thenReturn(List.of());
        AssetSpec spec = new AssetSpec("my drone", DRONE, Map.of(), List.of(), List.of(existing.id()));

        Asset created = service.createFromCandidate(spec, ownership, actingUser);

        assertEquals(Set.of(existing.id()), created.devices());
        verify(deviceService, never()).register(any(), any());
    }

    // --- Reading -------------------------------------------------------------

    @Test
    void summaryStatusIsStreamingWhenAnyAssetDeviceHasAnActiveStream() {
        Device cam = device("cam-1");
        Asset asset = asset(Set.of(cam.id()));
        when(assetRepository.findAll()).thenReturn(List.of(asset));
        when(assetLiveStatePort.activeStreamsByDevice()).thenReturn(Map.of(cam.id(), StreamId.random()));

        List<AssetSummary> summaries = service.assets();

        assertEquals(1, summaries.size());
        assertEquals(AssetStatus.STREAMING, summaries.get(0).status());
    }

    @Test
    void summaryStatusIsOfflineWhenNoAssetDeviceHasAnActiveStream() {
        Device cam = device("cam-1");
        Asset asset = asset(Set.of(cam.id()));
        when(assetRepository.findAll()).thenReturn(List.of(asset));
        when(assetLiveStatePort.activeStreamsByDevice()).thenReturn(Map.of());

        List<AssetSummary> summaries = service.assets();

        assertEquals(AssetStatus.OFFLINE, summaries.get(0).status());
    }

    @Test
    void summaryDerivesLastUsedAtAndLastKnownPositionFromMostRecentUsage() {
        Device cam = device("cam-1");
        Asset asset = asset(Set.of(cam.id()));
        GeoPosition start = new GeoPosition(50.0, 30.0, null);
        GeoPosition last = new GeoPosition(50.01, 30.01, null);
        Instant startedAt = Instant.now().minusSeconds(60);
        AssetUsage usage = new AssetUsage(UsageId.random(), asset.id(), startedAt, null, start, last, 5, null,
                UsagePhase.PREFLIGHT, UsageOrigin.STREAM);
        when(assetRepository.findAll()).thenReturn(List.of(asset));
        when(usageRepository.findRecentByAsset(eq(asset.id()), eq(1))).thenReturn(List.of(usage));

        AssetSummary summary = service.assets().get(0);

        assertEquals(startedAt, summary.lastUsedAt());
        assertEquals(last, summary.lastKnownPosition());
    }

    @Test
    void summaryFallsBackToStartPositionWhenNoLastPositionYet() {
        Device cam = device("cam-1");
        Asset asset = asset(Set.of(cam.id()));
        GeoPosition start = new GeoPosition(50.0, 30.0, null);
        AssetUsage usage = new AssetUsage(UsageId.random(), asset.id(), Instant.now(), null, start, null, 0, null,
                UsagePhase.PREFLIGHT, UsageOrigin.STREAM);
        when(assetRepository.findAll()).thenReturn(List.of(asset));
        when(usageRepository.findRecentByAsset(eq(asset.id()), eq(1))).thenReturn(List.of(usage));

        AssetSummary summary = service.assets().get(0);

        assertEquals(start, summary.lastKnownPosition());
    }

    @Test
    void summaryHasNullLastUsedAtWhenAssetHasNeverBeenUsed() {
        Asset asset = asset(Set.of(DeviceId.random()));
        when(assetRepository.findAll()).thenReturn(List.of(asset));

        AssetSummary summary = service.assets().get(0);

        assertEquals(null, summary.lastUsedAt());
        assertEquals(null, summary.lastKnownPosition());
    }

    @Test
    void detailsAssemblesSummaryDevicesAndRecentUsages() {
        Device cam = device("cam-1");
        Asset asset = asset(Set.of(cam.id()));
        AssetUsage usage = new AssetUsage(UsageId.random(), asset.id(), Instant.now(), null, null, null, 3, null,
                UsagePhase.PREFLIGHT, UsageOrigin.STREAM);
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        when(deviceService.find(cam.id())).thenReturn(Optional.of(cam));
        when(usageRepository.findRecentByAsset(eq(asset.id()), anyInt())).thenReturn(List.of(usage));

        AssetDetails details = service.details(asset.id());

        assertEquals(asset, details.summary().asset());
        assertEquals(List.of(cam), details.devices());
        assertEquals(List.of(usage), details.recentUsages());
    }

    @Test
    void detailsSkipsDevicesThatNoLongerResolve() {
        DeviceId missing = DeviceId.random();
        Asset asset = asset(Set.of(missing));
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        when(deviceService.find(missing)).thenReturn(Optional.empty());

        AssetDetails details = service.details(asset.id());

        assertTrue(details.devices().isEmpty());
    }

    @Test
    void detailsThrowsForUnknownAsset() {
        AssetId unknown = AssetId.random();
        when(assetRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.details(unknown));
    }

    // --- Listing hides soft-deleted assets -----------------------------------

    @Test
    void assetsHidesSoftDeletedOnesByDefaultButCanIncludeThem() {
        Asset live = asset(Set.of(DeviceId.random()));
        Asset gone = asset(Set.of(DeviceId.random())).withState(LifecycleState.DELETED);
        when(assetRepository.findAll()).thenReturn(List.of(live, gone));

        assertEquals(List.of(live), service.assets().stream().map(AssetSummary::asset).toList());
        assertEquals(List.of(live, gone), service.assets(true).stream().map(AssetSummary::asset).toList());
    }

    // --- Scoped reads (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2, feature 1) ----------

    @Test
    void scopedAssetsUnboundedReturnsExactlyTheUnscopedResult() {
        Asset live = asset(Set.of(DeviceId.random()));
        Asset gone = asset(Set.of(DeviceId.random())).withState(LifecycleState.DELETED);
        when(assetRepository.findAll()).thenReturn(List.of(live, gone));

        assertEquals(service.assets(false), service.assets(VisibilityScope.unbounded(), false));
        assertEquals(service.assets(true), service.assets(VisibilityScope.unbounded(), true));
    }

    @Test
    void scopedAssetsGroupScopeKeepsOnlyAssetsOwnedByAScopedGroup() {
        GroupId groupA = GroupId.random();
        GroupId groupB = GroupId.random();
        Asset inA = new Asset(AssetId.random(), "A", DRONE, new Ownership(actingUser, groupA),
                Set.of(DeviceId.random()), Map.of());
        Asset inB = new Asset(AssetId.random(), "B", DRONE, new Ownership(actingUser, groupB),
                Set.of(DeviceId.random()), Map.of());
        when(assetRepository.findAll()).thenReturn(List.of(inA, inB));

        List<Asset> visible = service.assets(VisibilityScope.groups(Set.of(groupA)), false).stream()
                .map(AssetSummary::asset).toList();

        assertEquals(List.of(inA), visible);
    }

    @Test
    void scopedDetailsThrowsNoSuchElementWhenAssetIsOutOfScope() {
        Asset stored = new Asset(AssetId.random(), "A", DRONE, new Ownership(actingUser, GroupId.random()),
                Set.of(DeviceId.random()), Map.of());
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));

        assertThrows(NoSuchElementException.class,
                () -> service.details(VisibilityScope.groups(Set.of()), stored.id()));
    }

    @Test
    void scopedDetailsUnboundedReturnsTheSameDetails() {
        Asset stored = asset(Set.of(DeviceId.random()));
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));

        assertEquals(service.details(stored.id()),
                service.details(VisibilityScope.unbounded(), stored.id()));
    }

    // --- Editing -------------------------------------------------------------

    @Test
    void updateAppliesOnlyTheFieldsSentAndLeavesTheRestAlone() {
        Asset stored = asset(Set.of(DeviceId.random()));
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));

        Asset updated = service.update(stored.id(), new AssetEdit("renamed drone", null, null), actingUser);

        assertEquals("renamed drone", updated.displayName());
        assertEquals(stored.category(), updated.category());
        assertEquals(stored.attributes(), updated.attributes());
        assertEquals(stored.ownership(), updated.ownership());
        assertEquals(stored.devices(), updated.devices());
    }

    @Test
    void updateRecordsWhatActuallyChanged() {
        Asset stored = asset(Set.of(DeviceId.random()));
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));

        service.update(stored.id(), new AssetEdit("renamed drone", null, null), actingUser);

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditTrail).record(captor.capture());
        assertEquals(AuditAction.UPDATED, captor.getValue().action());
        assertEquals(actingUser, captor.getValue().actor());
        assertTrue(captor.getValue().details().get("displayName").contains("renamed drone"));
    }

    @Test
    void updateAppliesAnAttributesOnlyEditIncludingRegistrationNumber() {
        // Confirms attributes patch end-to-end (docs/plans/done/UX-REWORK-PLAN.md §U-d item 3): the wizard's
        // registrationNumber field is just an attributes key, no special-cased field needed.
        Asset stored = asset(Set.of(DeviceId.random()));
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));

        Asset updated = service.update(stored.id(),
                new AssetEdit(null, null, Map.of("registrationNumber", "N12345")), actingUser);

        assertEquals(Map.of("registrationNumber", "N12345"), updated.attributes());
        assertEquals(stored.displayName(), updated.displayName());
        assertEquals(stored.category(), updated.category());
    }

    @Test
    void updateRejectsAnUnknownCategoryAndLeavesTheAssetAlone() {
        Asset stored = asset(Set.of(DeviceId.random()));
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));
        CategoryId missing = new CategoryId("nope");
        when(categoryRepository.findById(missing)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.update(stored.id(), new AssetEdit(null, missing, null), actingUser));
        verify(assetRepository, never()).save(any());
    }

    // --- Deactivating --------------------------------------------------------

    @Test
    void deactivatingAnAssetStopsEveryStreamItsDevicesAreRunning() {
        DeviceId one = DeviceId.random();
        DeviceId two = DeviceId.random();
        Asset stored = asset(Set.of(one, two));
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));
        when(assetLiveStatePort.stopStreamsForDevices(stored.devices())).thenReturn(1);

        Asset result = service.setState(stored.id(), LifecycleState.DEACTIVATED, actingUser);

        assertEquals(LifecycleState.DEACTIVATED, result.state());
        // Stopping is delegated wholesale to the port -- which devices actually had a running
        // stream is StreamBackedAssetLiveState's own concern, tested there.
        verify(assetLiveStatePort).stopStreamsForDevices(stored.devices());
    }

    // --- Deleting (soft) -----------------------------------------------------

    @Test
    void deleteMarksTheAssetAndItsDevicesButKeepsEveryUsage() {
        Device first = device("cam-a");
        Device second = device("cam-b");
        Asset stored = asset(Set.of(first.id(), second.id()));
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));
        when(deviceService.find(first.id())).thenReturn(Optional.of(first));
        when(deviceService.find(second.id())).thenReturn(Optional.of(second));
        when(usageRepository.findRecentByAsset(eq(stored.id()), anyInt()))
                .thenReturn(List.of(usage(stored.id()), usage(stored.id()), usage(stored.id())));

        AssetDeletion deletion = service.delete(stored.id(), actingUser);

        assertEquals(2, deletion.devicesDeleted());
        assertEquals(3, deletion.usagesRetained());

        ArgumentCaptor<Asset> assetCaptor = ArgumentCaptor.forClass(Asset.class);
        verify(assetRepository).save(assetCaptor.capture());
        assertEquals(LifecycleState.DELETED, assetCaptor.getValue().state());

        // Devices are deleted through DeviceService, so its own soft-delete rules apply to them.
        verify(deviceService).delete(first.id(), actingUser);
        verify(deviceService).delete(second.id(), actingUser);

        // Nothing is destroyed — that is the whole contract of a soft delete.
        verify(assetRepository, never()).deleteById(any());
    }

    @Test
    void deleteLeavesAlreadyDeletedDevicesAloneAndDoesNotCountThem() {
        Device live = device("cam-a");
        Device gone = device("cam-b").withState(LifecycleState.DELETED);
        Asset stored = asset(Set.of(live.id(), gone.id()));
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));
        when(deviceService.find(live.id())).thenReturn(Optional.of(live));
        when(deviceService.find(gone.id())).thenReturn(Optional.of(gone));

        AssetDeletion deletion = service.delete(stored.id(), actingUser);

        assertEquals(1, deletion.devicesDeleted());
        verify(deviceService, never()).delete(eq(gone.id()), any());
    }

    @Test
    void deleteIsIdempotentAndTouchesNothingTheSecondTime() {
        Asset stored = asset(Set.of(DeviceId.random())).withState(LifecycleState.DELETED);
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));

        AssetDeletion deletion = service.delete(stored.id(), actingUser);

        assertEquals(0, deletion.streamsStopped());
        verify(assetRepository, never()).save(any());
        verify(auditTrail, never()).record(any());
    }

    @Test
    void deleteThrowsForUnknownAsset() {
        AssetId unknown = AssetId.random();
        when(assetRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.delete(unknown, actingUser));
    }

    // --- Restoring -----------------------------------------------------------

    @Test
    void aDeletedAssetCannotBeActivatedStraightBackOntoTheAir() {
        Asset stored = asset(Set.of(DeviceId.random())).withState(LifecycleState.DELETED);
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.setState(stored.id(), LifecycleState.ACTIVE, actingUser));

        assertTrue(thrown.getMessage().contains("restore"));
        verify(assetRepository, never()).save(any());
    }

    @Test
    void restoringADeletedAssetBringsItBackOutOfService() {
        Asset stored = asset(Set.of(DeviceId.random())).withState(LifecycleState.DELETED);
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));

        Asset restored = service.setState(stored.id(), LifecycleState.DEACTIVATED, actingUser);

        assertEquals(LifecycleState.DEACTIVATED, restored.state());
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditTrail).record(captor.capture());
        assertEquals(AuditAction.RESTORED, captor.getValue().action());
    }

    // --- Streaming: stopping --------------------------------------------------
    // Starting a stream moved to perception.application.stream.DefaultAssetStreamServiceTest
    // (docs/plans/active/DOMAIN-SEPARATION-W1.md §15, W1.6e); stopping stays here, delegated
    // wholesale to AssetLiveStatePort.

    @Test
    void stopStreamDelegatesToThePortWithTheAssetsWholeDeviceSet() {
        // Which of these devices actually has a running stream is StreamBackedAssetLiveState's own
        // concern (tested there) -- this service's job is only to resolve the asset and hand its
        // device set to the port wholesale.
        DeviceId one = DeviceId.random();
        DeviceId two = DeviceId.random();
        Asset stored = asset(Set.of(one, two));
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));
        when(assetLiveStatePort.stopStreamsForDevices(stored.devices())).thenReturn(1);

        service.stopStream(stored.id());

        verify(assetLiveStatePort).stopStreamsForDevices(stored.devices());
    }

    @Test
    void stopStreamIsANoOpForAnUnknownAsset() {
        AssetId unknown = AssetId.random();
        when(assetRepository.findById(unknown)).thenReturn(Optional.empty());

        service.stopStream(unknown);

        // Unknown asset: never even resolves to a call against the port.
        verifyNoInteractions(assetLiveStatePort);
    }

    // --- Assigning devices -----------------------------------------------------

    @Test
    void assignDeviceAddsAnUnownedDeviceAndAuditsUpdated() {
        Device cam = device("cam-1");
        Device newCam = device("cam-2");
        Asset stored = asset(Set.of(cam.id()));
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));
        when(deviceService.find(newCam.id())).thenReturn(Optional.of(newCam));
        when(assetRepository.findByDeviceId(newCam.id())).thenReturn(Optional.empty());

        Asset updated = service.assignDevice(stored.id(), newCam.id(), actingUser);

        assertEquals(Set.of(cam.id(), newCam.id()), updated.devices());
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditTrail).record(captor.capture());
        assertEquals(AuditAction.UPDATED, captor.getValue().action());
    }

    @Test
    void assignDeviceThrowsForUnknownDevice() {
        Asset stored = asset(Set.of(DeviceId.random()));
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));
        DeviceId unknown = DeviceId.random();
        when(deviceService.find(unknown)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> service.assignDevice(stored.id(), unknown, actingUser));
        verify(assetRepository, never()).save(any());
    }

    @Test
    void assignDeviceRejectsADeletedDevice() {
        Asset stored = asset(Set.of(DeviceId.random()));
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));
        Device deleted = device("gone").withState(LifecycleState.DELETED);
        when(deviceService.find(deleted.id())).thenReturn(Optional.of(deleted));

        assertThrows(IllegalArgumentException.class,
                () -> service.assignDevice(stored.id(), deleted.id(), actingUser));
        verify(assetRepository, never()).save(any());
    }

    @Test
    void assignDeviceRefusesADeviceAlreadyOwnedByAnotherAsset() {
        Asset stored = asset(Set.of(DeviceId.random()));
        Asset other = asset(Set.of(DeviceId.random()));
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));
        Device owned = device("owned-elsewhere");
        when(deviceService.find(owned.id())).thenReturn(Optional.of(owned));
        when(assetRepository.findByDeviceId(owned.id())).thenReturn(Optional.of(other));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.assignDevice(stored.id(), owned.id(), actingUser));

        assertTrue(thrown.getMessage().contains(other.displayName()));
        verify(assetRepository, never()).save(any());
    }

    @Test
    void assignDeviceThrowsForUnknownAsset() {
        AssetId unknown = AssetId.random();
        when(assetRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> service.assignDevice(unknown, DeviceId.random(), actingUser));
    }

    // --- Unassigning devices -----------------------------------------------------

    @Test
    void unassignDeviceRemovesItAndAuditsUpdated() {
        Device first = device("cam-a");
        Device second = device("cam-b");
        Asset stored = asset(Set.of(first.id(), second.id()));
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));

        Asset updated = service.unassignDevice(stored.id(), second.id(), actingUser);

        assertEquals(Set.of(first.id()), updated.devices());
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditTrail).record(captor.capture());
        assertEquals(AuditAction.UPDATED, captor.getValue().action());
    }

    @Test
    void unassignDeviceRejectsADeviceThatDoesNotBelongToTheAsset() {
        Asset stored = asset(Set.of(DeviceId.random(), DeviceId.random()));
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));
        DeviceId stranger = DeviceId.random();

        assertThrows(IllegalArgumentException.class,
                () -> service.unassignDevice(stored.id(), stranger, actingUser));
        verify(assetRepository, never()).save(any());
    }

    @Test
    void unassignDeviceRefusesToRemoveTheLastDevice() {
        Device only = device("cam-only");
        Asset stored = asset(Set.of(only.id()));
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.unassignDevice(stored.id(), only.id(), actingUser));

        assertTrue(thrown.getMessage().contains("at least one device"));
        verify(assetRepository, never()).save(any());
    }

    @Test
    void unassignDeviceThrowsForUnknownAsset() {
        AssetId unknown = AssetId.random();
        when(assetRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> service.unassignDevice(unknown, DeviceId.random(), actingUser));
    }

    private static AssetUsage usage(AssetId assetId) {
        return new AssetUsage(UsageId.random(), assetId, Instant.now(), null, null, null, 0, null,
                UsagePhase.PREFLIGHT, UsageOrigin.STREAM);
    }

    private static DeviceRegistration registration(String name) {
        return new DeviceRegistration(name, Set.of(Capability.VIDEO),
                new StreamDescriptor("sim", URI.create("sim://" + name), Map.of()));
    }

    private static Device device(String name) {
        return new Device(DeviceId.random(), name, Set.of(Capability.VIDEO),
                new StreamDescriptor("sim", URI.create("sim://" + name), Map.of()));
    }

    private Asset asset(Set<DeviceId> devices) {
        return new Asset(AssetId.random(), "my drone", DRONE, ownership, devices, Map.of());
    }
}

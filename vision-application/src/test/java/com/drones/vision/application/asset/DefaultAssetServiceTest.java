package com.drones.vision.application.asset;

import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.AssetUsage;
import com.drones.vision.domain.model.AuditAction;
import com.drones.vision.domain.model.AuditEntry;
import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceCategory;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.GeoPosition;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.LifecycleState;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.UsageId;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.AssetRepositoryPort;
import com.drones.vision.domain.port.out.AssetUsageRepositoryPort;
import com.drones.vision.domain.port.out.AuditTrailPort;
import com.drones.vision.domain.port.out.CategoryRepositoryPort;
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
import com.drones.vision.application.device.DeviceRegistration;
import com.drones.vision.application.device.DeviceService;
import com.drones.vision.application.scope.VisibilityScope;
import com.drones.vision.application.stream.ActiveStream;
import com.drones.vision.application.stream.StreamService;
import com.drones.vision.application.stream.TrackingConfigPatch;

class DefaultAssetServiceTest {

    private static final CategoryId DRONE = new CategoryId("drone");

    private AssetRepositoryPort assetRepository;
    private CategoryRepositoryPort categoryRepository;
    private AssetUsageRepositoryPort usageRepository;
    private AuditTrailPort auditTrail;
    private DeviceService deviceService;
    private StreamService streamService;
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
        streamService = mock(StreamService.class);
        actingUser = UserId.random();
        ownership = new Ownership(actingUser, GroupId.random());

        service = new DefaultAssetService(assetRepository, categoryRepository, usageRepository, auditTrail,
                deviceService, streamService);

        when(assetRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(streamService.streams()).thenReturn(List.of());
        when(categoryRepository.findById(DRONE))
                .thenReturn(Optional.of(new DeviceCategory(DRONE, "Drone", null, List.of())));
        when(streamService.activeDeviceIds()).thenReturn(Set.of());
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

    // --- Reading -------------------------------------------------------------

    @Test
    void summaryStatusIsStreamingWhenAnyAssetDeviceHasAnActiveStream() {
        Device cam = device("cam-1");
        Asset asset = asset(Set.of(cam.id()));
        when(assetRepository.findAll()).thenReturn(List.of(asset));
        when(streamService.activeDeviceIds()).thenReturn(Set.of(cam.id()));

        List<AssetSummary> summaries = service.assets();

        assertEquals(1, summaries.size());
        assertEquals(AssetStatus.STREAMING, summaries.get(0).status());
    }

    @Test
    void summaryStatusIsOfflineWhenNoAssetDeviceHasAnActiveStream() {
        Device cam = device("cam-1");
        Asset asset = asset(Set.of(cam.id()));
        when(assetRepository.findAll()).thenReturn(List.of(asset));
        when(streamService.activeDeviceIds()).thenReturn(Set.of());

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
        AssetUsage usage = new AssetUsage(UsageId.random(), asset.id(), startedAt, null, start, last, 5);
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
        AssetUsage usage = new AssetUsage(UsageId.random(), asset.id(), Instant.now(), null, start, null, 0);
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
        AssetUsage usage = new AssetUsage(UsageId.random(), asset.id(), Instant.now(), null, null, null, 3);
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
        StreamId streamOne = StreamId.random();
        StreamId elsewhere = StreamId.random();
        when(streamService.streams()).thenReturn(List.of(
                new ActiveStream(streamOne, one, Instant.now()),
                new ActiveStream(elsewhere, DeviceId.random(), Instant.now())));

        Asset result = service.setState(stored.id(), LifecycleState.DEACTIVATED, actingUser);

        assertEquals(LifecycleState.DEACTIVATED, result.state());
        verify(streamService).stop(streamOne);
        verify(streamService, never()).stop(elsewhere); // another asset's stream is untouched
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

    // --- Streaming: resolving which device to start ---------------------------

    @Test
    void startStreamResolvesTheAssetsOnlyActiveVideoCapableDeviceWhenNoneIsNamed() {
        Device cam = device("cam-1");
        Device telemetry = telemetryDevice("tel-1");
        Asset stored = asset(Set.of(cam.id(), telemetry.id()));
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));
        when(deviceService.find(cam.id())).thenReturn(Optional.of(cam));
        when(deviceService.find(telemetry.id())).thenReturn(Optional.of(telemetry));
        StreamId expected = StreamId.random();
        when(streamService.start(eq(cam.id()), any(), any())).thenReturn(expected);

        StreamId started = service.startStream(stored.id(), null, PipelineConfig.defaults());

        assertEquals(expected, started);
        verify(streamService).start(cam.id(), PipelineConfig.defaults(), TrackingConfigPatch.NOTHING);
    }

    @Test
    void startStreamRefusesToGuessWhenTheAssetHasSeveralVideoCapableDevices() {
        Device first = device("cam-a");
        Device second = device("cam-b");
        Asset stored = asset(Set.of(first.id(), second.id()));
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));
        when(deviceService.find(first.id())).thenReturn(Optional.of(first));
        when(deviceService.find(second.id())).thenReturn(Optional.of(second));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> service.startStream(stored.id(), null, PipelineConfig.defaults()));

        // The message must name both candidates, so the caller can pick one without a second lookup.
        assertTrue(thrown.getMessage().contains(first.id().value().toString())
                        && thrown.getMessage().contains(second.id().value().toString()),
                "expected message to name both ambiguous candidates: " + thrown.getMessage());
        verify(streamService, never()).start(any(), any());
    }

    @Test
    void startStreamRejectsADeviceThatDoesNotBelongToTheAsset() {
        Device cam = device("cam-1");
        Device stranger = device("someone-elses-cam");
        Asset stored = asset(Set.of(cam.id()));
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));

        assertThrows(IllegalArgumentException.class,
                () -> service.startStream(stored.id(), stranger.id(), PipelineConfig.defaults()));
        verify(streamService, never()).start(any(), any());
    }

    @Test
    void startStreamUsesTheNamedDeviceWhenItBelongsToTheAsset() {
        Device first = device("cam-a");
        Device second = device("cam-b");
        Asset stored = asset(Set.of(first.id(), second.id()));
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));
        StreamId expected = StreamId.random();
        when(streamService.start(eq(second.id()), any(), any())).thenReturn(expected);

        StreamId started = service.startStream(stored.id(), second.id(), PipelineConfig.defaults());

        // Naming a device settles the choice outright: no resolution pass runs, so two
        // video-capable devices are not ambiguous here.
        assertEquals(expected, started);
        verify(deviceService, never()).find(any());
    }

    @Test
    void startStreamRefusesWhenTheAssetIsNotInService() {
        Device cam = device("cam-1");
        Asset stored = asset(Set.of(cam.id())).withState(LifecycleState.DEACTIVATED);
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));

        assertThrows(IllegalStateException.class,
                () -> service.startStream(stored.id(), cam.id(), PipelineConfig.defaults()));
        verify(streamService, never()).start(any(), any());
    }

    @Test
    void startStreamSkipsDeactivatedAndDeletedDevicesWhenResolving() {
        Device working = device("cam-working");
        Device retired = device("cam-retired").withState(LifecycleState.DEACTIVATED);
        Device removed = device("cam-removed").withState(LifecycleState.DELETED);
        Asset stored = asset(Set.of(working.id(), retired.id(), removed.id()));
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));
        when(deviceService.find(working.id())).thenReturn(Optional.of(working));
        when(deviceService.find(retired.id())).thenReturn(Optional.of(retired));
        when(deviceService.find(removed.id())).thenReturn(Optional.of(removed));

        service.startStream(stored.id(), null, PipelineConfig.defaults());

        // Out-of-service sources are invisible to resolution, so one working camera among
        // three video-capable ones is not ambiguity.
        verify(streamService).start(working.id(), PipelineConfig.defaults(), TrackingConfigPatch.NOTHING);
    }

    @Test
    void startStreamRefusesWhenNoDeviceOfTheAssetCanProduceVideo() {
        Device telemetry = telemetryDevice("tel-1");
        Asset stored = asset(Set.of(telemetry.id()));
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));
        when(deviceService.find(telemetry.id())).thenReturn(Optional.of(telemetry));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> service.startStream(stored.id(), null, PipelineConfig.defaults()));

        assertTrue(thrown.getMessage().contains("no active video-capable device"));
        verify(streamService, never()).start(any(), any());
    }

    @Test
    void startStreamThrowsForUnknownAsset() {
        AssetId unknown = AssetId.random();
        when(assetRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> service.startStream(unknown, null, PipelineConfig.defaults()));
    }

    // --- Streaming: stopping --------------------------------------------------

    @Test
    void stopStreamStopsEveryStreamTheAssetsDevicesAreRunning() {
        DeviceId mine = DeviceId.random();
        Asset stored = asset(Set.of(mine));
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));
        StreamId ours = StreamId.random();
        StreamId elsewhere = StreamId.random();
        when(streamService.streams()).thenReturn(List.of(
                new ActiveStream(ours, mine, Instant.now()),
                new ActiveStream(elsewhere, DeviceId.random(), Instant.now())));

        service.stopStream(stored.id());

        verify(streamService).stop(ours);
        verify(streamService, never()).stop(elsewhere);
    }

    @Test
    void stopStreamIsANoOpForAnAssetThatIsNotStreaming() {
        Asset stored = asset(Set.of(DeviceId.random()));
        when(assetRepository.findById(stored.id())).thenReturn(Optional.of(stored));
        when(streamService.streams()).thenReturn(List.of());

        service.stopStream(stored.id());

        verify(streamService, never()).stop(any());
    }

    @Test
    void stopStreamIsANoOpForAnUnknownAsset() {
        AssetId unknown = AssetId.random();
        when(assetRepository.findById(unknown)).thenReturn(Optional.empty());

        service.stopStream(unknown);

        // Mirrors StreamService#stop's idempotency: stopping what is not there is not an error.
        verify(streamService, never()).stop(any());
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
        return new AssetUsage(UsageId.random(), assetId, Instant.now(), null, null, null, 0);
    }

    private static DeviceRegistration registration(String name) {
        return new DeviceRegistration(name, Set.of(Capability.VIDEO),
                new StreamDescriptor("sim", URI.create("sim://" + name), Map.of()));
    }

    private static Device device(String name) {
        return new Device(DeviceId.random(), name, Set.of(Capability.VIDEO),
                new StreamDescriptor("sim", URI.create("sim://" + name), Map.of()));
    }

    private static Device telemetryDevice(String name) {
        return new Device(DeviceId.random(), name, Set.of(Capability.TELEMETRY),
                new StreamDescriptor("sim", URI.create("sim://" + name), Map.of()));
    }

    private Asset asset(Set<DeviceId> devices) {
        return new Asset(AssetId.random(), "my drone", DRONE, ownership, devices, Map.of());
    }
}

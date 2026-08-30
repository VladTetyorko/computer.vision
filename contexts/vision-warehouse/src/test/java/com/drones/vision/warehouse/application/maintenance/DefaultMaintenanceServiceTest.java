package com.drones.vision.warehouse.application.maintenance;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.Identity;
import com.drones.vision.warehouse.domain.model.MaintenanceId;
import com.drones.vision.warehouse.domain.model.MaintenanceKind;
import com.drones.vision.warehouse.domain.model.MaintenanceRecord;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import com.drones.vision.warehouse.domain.port.MaintenanceRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultMaintenanceServiceTest {

    private static final CategoryId DRONE = new CategoryId("drone");

    private MaintenanceRepositoryPort maintenanceRepository;
    private AssetRepositoryPort assetRepository;
    private AuditTrailPort auditTrail;
    private DefaultMaintenanceService service;

    private final UserId actor = UserId.random();
    private Ownership ownership;
    private VisibilityScope inScope;
    private VisibilityScope outOfScope;
    private Asset asset;

    @BeforeEach
    void setUp() {
        maintenanceRepository = mock(MaintenanceRepositoryPort.class);
        assetRepository = mock(AssetRepositoryPort.class);
        auditTrail = mock(AuditTrailPort.class);
        service = new DefaultMaintenanceService(maintenanceRepository, assetRepository, auditTrail);

        ownership = new Ownership(actor, GroupId.random());
        inScope = VisibilityScope.groups(Set.of(ownership.groupId()));
        outOfScope = VisibilityScope.groups(Set.of(GroupId.random()));
        asset = Asset.register(AssetId.random(), "my drone", DRONE, ownership, Set.of(DeviceId.random()), Map.of(),
                Identity.NONE, Custody.NONE);
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        when(maintenanceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    // -- open -----------------------------------------------------------------------------------

    @Test
    void openCreatesARecordAgainstTheAssetWithoutTouchingItsInventoryState() {
        MaintenanceRecord record = service.open(asset.id(), MaintenanceKind.REPAIR, "cracked prop", actor, inScope);

        assertEquals(asset.id(), record.assetId());
        assertEquals(MaintenanceKind.REPAIR, record.kind());
        assertTrue(record.isOpen());
        verify(auditTrail).record(any());
    }

    @Test
    void openRefusesOutOfScope() {
        assertThrows(AccessDeniedException.class,
                () -> service.open(asset.id(), MaintenanceKind.REPAIR, "cracked prop", actor, outOfScope));
        verify(maintenanceRepository, never()).save(any());
    }

    @Test
    void openThrowsForAnUnknownAsset() {
        AssetId unknown = AssetId.random();
        when(assetRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> service.open(unknown, MaintenanceKind.REPAIR, "cracked prop", actor, inScope));
    }

    @Test
    void openRejectsABlankSummary() {
        assertThrows(IllegalArgumentException.class,
                () -> service.open(asset.id(), MaintenanceKind.REPAIR, " ", actor, inScope));
    }

    // -- close ----------------------------------------------------------------------------------

    @Test
    void closeClosesAnOpenRecord() {
        MaintenanceRecord open = new MaintenanceRecord(MaintenanceId.random(), asset.id(), MaintenanceKind.NOTE,
                Instant.now(), null, actor, "just a note", null);
        when(maintenanceRepository.findById(open.id())).thenReturn(Optional.of(open));

        MaintenanceRecord closed = service.close(open.id(), actor, inScope);

        assertFalse(closed.isOpen());
        verify(auditTrail).record(any());
    }

    @Test
    void closeRefusesOutOfScope() {
        MaintenanceRecord open = new MaintenanceRecord(MaintenanceId.random(), asset.id(), MaintenanceKind.NOTE,
                Instant.now(), null, actor, "just a note", null);
        when(maintenanceRepository.findById(open.id())).thenReturn(Optional.of(open));

        assertThrows(AccessDeniedException.class, () -> service.close(open.id(), actor, outOfScope));
        verify(maintenanceRepository, never()).save(any());
    }

    @Test
    void closeThrowsForAnUnknownRecord() {
        MaintenanceId unknown = MaintenanceId.random();
        when(maintenanceRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.close(unknown, actor, inScope));
    }

    // -- listForAsset -----------------------------------------------------------------------------

    @Test
    void listForAssetReturnsTheFullHistoryOpenAndClosed() {
        MaintenanceRecord open = new MaintenanceRecord(MaintenanceId.random(), asset.id(), MaintenanceKind.GROUNDING,
                Instant.now(), null, actor, "prop strike", null);
        MaintenanceRecord closed = open.close(Instant.now());
        when(maintenanceRepository.findByAsset(asset.id())).thenReturn(List.of(open, closed));

        List<MaintenanceRecord> history = service.listForAsset(asset.id(), inScope);

        assertEquals(2, history.size());
    }

    @Test
    void listForAssetHidesAnOutOfScopeAssetBehind404() {
        assertThrows(NoSuchElementException.class, () -> service.listForAsset(asset.id(), outOfScope));
    }

    @Test
    void listForAssetThrowsForAnUnknownAsset() {
        AssetId unknown = AssetId.random();
        when(assetRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.listForAsset(unknown, inScope));
    }

    // -- openBlockers (MaintenanceQuery) -----------------------------------------------------------

    @Test
    void openBlockersReturnsOnlyFlightBlockingOpenRecords() {
        MaintenanceRecord grounding = new MaintenanceRecord(MaintenanceId.random(), asset.id(),
                MaintenanceKind.GROUNDING, Instant.now(), null, actor, "prop strike", null);
        MaintenanceRecord note = new MaintenanceRecord(MaintenanceId.random(), asset.id(), MaintenanceKind.NOTE,
                Instant.now(), null, actor, "just a note", null);
        when(maintenanceRepository.findOpenByAsset(asset.id())).thenReturn(List.of(grounding, note));

        List<MaintenanceRecord> blockers = service.openBlockers(asset.id());

        assertEquals(List.of(grounding), blockers);
    }

    @Test
    void openBlockersIsEmptyWhenNothingIsOpen() {
        when(maintenanceRepository.findOpenByAsset(asset.id())).thenReturn(List.of());

        assertTrue(service.openBlockers(asset.id()).isEmpty());
    }

    @Test
    void openBlockersIsUnscopedByDesign() {
        // MaintenanceQuery is an internal, service-to-service read (the caller already resolved
        // and scope-checked the asset itself) -- it takes no VisibilityScope at all.
        MaintenanceRecord grounding = new MaintenanceRecord(MaintenanceId.random(), asset.id(),
                MaintenanceKind.GROUNDING, Instant.now(), null, actor, "prop strike", null);
        when(maintenanceRepository.findOpenByAsset(asset.id())).thenReturn(List.of(grounding));

        MaintenanceQuery query = service;

        assertEquals(1, query.openBlockers(asset.id()).size());
    }

    // -- fleetWide --------------------------------------------------------------------------------

    @Test
    void fleetWideOpenJoinsTheAssetNameAndCategoryAndIgnoresTheLimit() {
        MaintenanceRecord open = new MaintenanceRecord(MaintenanceId.random(), asset.id(), MaintenanceKind.GROUNDING,
                Instant.now(), null, actor, "prop strike", null);
        when(maintenanceRepository.findOpen()).thenReturn(List.of(open));

        List<MaintenanceRecordSummary> fleetWide = service.fleetWide(MaintenanceListState.OPEN, 1, inScope);

        assertEquals(1, fleetWide.size());
        assertEquals(open, fleetWide.get(0).record());
        assertEquals(asset.displayName(), fleetWide.get(0).assetName());
        assertEquals(asset.category(), fleetWide.get(0).categoryId());
    }

    @Test
    void fleetWideClosedDelegatesTheLimitToTheRepository() {
        MaintenanceRecord open = new MaintenanceRecord(MaintenanceId.random(), asset.id(), MaintenanceKind.GROUNDING,
                Instant.now(), null, actor, "prop strike", null);
        MaintenanceRecord closed = open.close(Instant.now());
        when(maintenanceRepository.findRecentlyClosed(5)).thenReturn(List.of(closed));

        List<MaintenanceRecordSummary> fleetWide = service.fleetWide(MaintenanceListState.CLOSED, 5, inScope);

        assertEquals(List.of(closed), fleetWide.stream().map(MaintenanceRecordSummary::record).toList());
        verify(maintenanceRepository, never()).findOpen();
    }

    @Test
    void fleetWideAllConcatenatesOpenAndRecentlyClosed() {
        MaintenanceRecord open = new MaintenanceRecord(MaintenanceId.random(), asset.id(), MaintenanceKind.GROUNDING,
                Instant.now(), null, actor, "prop strike", null);
        MaintenanceRecord closed = new MaintenanceRecord(MaintenanceId.random(), asset.id(), MaintenanceKind.NOTE,
                Instant.now(), null, actor, "just a note", null).close(Instant.now());
        when(maintenanceRepository.findOpen()).thenReturn(List.of(open));
        when(maintenanceRepository.findRecentlyClosed(200)).thenReturn(List.of(closed));

        List<MaintenanceRecordSummary> fleetWide = service.fleetWide(MaintenanceListState.ALL, 200, inScope);

        assertEquals(2, fleetWide.size());
    }

    @Test
    void fleetWideSilentlyDropsRecordsForAssetsOutOfScope() {
        MaintenanceRecord open = new MaintenanceRecord(MaintenanceId.random(), asset.id(), MaintenanceKind.GROUNDING,
                Instant.now(), null, actor, "prop strike", null);
        when(maintenanceRepository.findOpen()).thenReturn(List.of(open));

        List<MaintenanceRecordSummary> fleetWide = service.fleetWide(MaintenanceListState.OPEN, 200, outOfScope);

        assertTrue(fleetWide.isEmpty());
    }

    @Test
    void fleetWideDropsRecordsForASoftDeletedAsset() {
        Asset deleted = asset.withState(com.drones.vision.kernel.LifecycleState.DELETED);
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(deleted));
        MaintenanceRecord open = new MaintenanceRecord(MaintenanceId.random(), asset.id(), MaintenanceKind.GROUNDING,
                Instant.now(), null, actor, "prop strike", null);
        when(maintenanceRepository.findOpen()).thenReturn(List.of(open));

        List<MaintenanceRecordSummary> fleetWide = service.fleetWide(MaintenanceListState.OPEN, 200, inScope);

        assertTrue(fleetWide.isEmpty());
    }

    @Test
    void fleetWideRejectsANonPositiveLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> service.fleetWide(MaintenanceListState.OPEN, 0, inScope));
    }
}

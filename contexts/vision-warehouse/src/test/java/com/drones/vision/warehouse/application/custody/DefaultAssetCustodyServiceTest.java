package com.drones.vision.warehouse.application.custody;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.platform.Authority;
import com.drones.vision.platform.Capability;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.Identity;
import com.drones.vision.warehouse.domain.model.InventoryState;
import com.drones.vision.warehouse.domain.model.MaintenanceId;
import com.drones.vision.warehouse.domain.model.MaintenanceKind;
import com.drones.vision.warehouse.domain.model.MaintenanceRecord;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import com.drones.vision.warehouse.domain.port.MaintenanceRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultAssetCustodyServiceTest {

    private static final CategoryId DRONE = new CategoryId("drone");

    private AssetRepositoryPort assetRepository;
    private MaintenanceRepositoryPort maintenanceRepository;
    private AuditTrailPort auditTrail;
    private DefaultAssetCustodyService service;

    private final UserId actor = UserId.random();
    private Ownership ownership;
    private Authority inScope;
    private Authority outOfScope;

    @BeforeEach
    void setUp() {
        assetRepository = mock(AssetRepositoryPort.class);
        maintenanceRepository = mock(MaintenanceRepositoryPort.class);
        auditTrail = mock(AuditTrailPort.class);
        service = new DefaultAssetCustodyService(assetRepository, maintenanceRepository, auditTrail);

        ownership = new Ownership(actor, GroupId.random());
        inScope = new Authority(VisibilityScope.groups(Set.of(ownership.groupId())), Set.of(Capability.MANAGE_FLEET));
        outOfScope = new Authority(VisibilityScope.groups(Set.of(GroupId.random())), Set.of(Capability.MANAGE_FLEET));

        // save() round-trips whatever it is given, mirroring an in-memory repository.
        when(assetRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(maintenanceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Asset inStockAsset() {
        return Asset.register(AssetId.random(), "my drone", DRONE, ownership, Set.of(DeviceId.random()), Map.of(),
                Identity.NONE, Custody.NONE);
    }

    private Asset issuedAsset(UserId custodianId) {
        Asset asset = inStockAsset();
        return asset.withInventory(new Custody(custodianId, "Hangar 2", Instant.now()), InventoryState.IN_STOCK,
                Instant.now());
    }

    private Asset inMaintenanceAsset(Custody custody) {
        Asset asset = inStockAsset();
        return asset.withInventory(custody, InventoryState.MAINTENANCE, Instant.now());
    }

    private Asset retiredAsset() {
        Asset asset = inStockAsset();
        return asset.withInventory(Custody.NONE, InventoryState.RETIRED, Instant.now());
    }

    // -- issue --------------------------------------------------------------------------------

    @Test
    void issueHandsAnInStockAssetToACustodian() {
        Asset asset = inStockAsset();
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        UserId custodian = UserId.random();

        Asset result = service.issue(asset.id(), custodian, "Hangar 2", actor, inScope);

        assertEquals(custodian, result.custody().custodianId());
        assertEquals("Hangar 2", result.custody().location());
        assertNotNull(result.custody().since());
        assertEquals(InventoryState.ISSUED, com.drones.vision.warehouse.domain.model.InventoryStates.effective(
                result, false));
        verify(auditTrail, times(1)).record(any());
    }

    @Test
    void issueRefusesAnAlreadyIssuedAsset() {
        Asset asset = issuedAsset(UserId.random());
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));

        assertThrows(IllegalStateException.class,
                () -> service.issue(asset.id(), UserId.random(), "Hangar 3", actor, inScope));
        verify(assetRepository, never()).save(any());
    }

    @Test
    void issueRefusesOutOfScope() {
        Asset asset = inStockAsset();
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));

        assertThrows(AccessDeniedException.class,
                () -> service.issue(asset.id(), UserId.random(), "Hangar 2", actor, outOfScope));
        verify(assetRepository, never()).save(any());
        verify(auditTrail, times(1)).record(any()); // denial is still audited
    }

    @Test
    void issueThrowsForAnUnknownAsset() {
        AssetId unknown = AssetId.random();
        when(assetRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> service.issue(unknown, UserId.random(), "Hangar 2", actor, inScope));
    }

    // -- returnToStock --------------------------------------------------------------------------

    @Test
    void returnToStockClearsCustodyAndKeepsStoredStateInStock() {
        Asset asset = issuedAsset(UserId.random());
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));

        Asset result = service.returnToStock(asset.id(), actor, inScope);

        assertEquals(Custody.NONE, result.custody());
        assertEquals(InventoryState.IN_STOCK, result.inventoryState());
    }

    @Test
    void returnToStockRefusesAnAssetThatIsNotIssued() {
        Asset asset = inStockAsset();
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));

        assertThrows(IllegalStateException.class, () -> service.returnToStock(asset.id(), actor, inScope));
    }

    @Test
    void returnToStockRefusesOutOfScope() {
        Asset asset = issuedAsset(UserId.random());
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));

        assertThrows(AccessDeniedException.class, () -> service.returnToStock(asset.id(), actor, outOfScope));
    }

    // -- ground ---------------------------------------------------------------------------------

    @Test
    void groundOpensAMaintenanceRecordAndSetsMaintenanceState() {
        Asset asset = inStockAsset();
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));

        Asset result = service.ground(asset.id(), MaintenanceKind.GROUNDING, "prop strike", actor, inScope);

        assertEquals(InventoryState.MAINTENANCE, result.inventoryState());
        verify(maintenanceRepository, times(1)).save(any());
    }

    @Test
    void groundKeepsExistingCustodyWhileGrounded() {
        UserId custodian = UserId.random();
        Asset asset = issuedAsset(custodian);
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));

        Asset result = service.ground(asset.id(), MaintenanceKind.INSPECTION_DUE, "annual inspection", actor,
                inScope);

        assertEquals(custodian, result.custody().custodianId());
        assertEquals(InventoryState.MAINTENANCE, result.inventoryState());
    }

    @Test
    void groundRefusesARetiredAsset() {
        Asset asset = retiredAsset();
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));

        assertThrows(IllegalStateException.class,
                () -> service.ground(asset.id(), MaintenanceKind.REPAIR, "post-retirement note", actor, inScope));
        verify(maintenanceRepository, never()).save(any());
    }

    @Test
    void groundRefusesOutOfScope() {
        Asset asset = inStockAsset();
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));

        assertThrows(AccessDeniedException.class,
                () -> service.ground(asset.id(), MaintenanceKind.GROUNDING, "prop strike", actor, outOfScope));
        verify(maintenanceRepository, never()).save(any());
    }

    @Test
    void groundRejectsABlankSummary() {
        Asset asset = inStockAsset();
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));

        assertThrows(IllegalArgumentException.class,
                () -> service.ground(asset.id(), MaintenanceKind.GROUNDING, " ", actor, inScope));
    }

    // -- release --------------------------------------------------------------------------------

    @Test
    void releaseClosesOpenBlockingRecordsAndReturnsToStock() {
        Asset asset = inMaintenanceAsset(new Custody(UserId.random(), "Hangar 2", Instant.now()));
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        MaintenanceRecord blocker = new MaintenanceRecord(MaintenanceId.random(), asset.id(),
                MaintenanceKind.GROUNDING, Instant.now(), null, actor, "prop strike", null);
        when(maintenanceRepository.findOpenByAsset(asset.id())).thenReturn(List.of(blocker));

        Asset result = service.release(asset.id(), actor, inScope);

        assertEquals(InventoryState.IN_STOCK, result.inventoryState());
        assertEquals(Custody.NONE, result.custody());
        verify(maintenanceRepository, times(1)).save(any());
    }

    @Test
    void releaseLeavesNonBlockingOpenRecordsOpen() {
        Asset asset = inMaintenanceAsset(Custody.NONE);
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
        MaintenanceRecord note = new MaintenanceRecord(MaintenanceId.random(), asset.id(), MaintenanceKind.NOTE,
                Instant.now(), null, actor, "just a note", null);
        when(maintenanceRepository.findOpenByAsset(asset.id())).thenReturn(List.of(note));

        service.release(asset.id(), actor, inScope);

        verify(maintenanceRepository, never()).save(any());
    }

    @Test
    void releaseRefusesAnAssetThatIsNotInMaintenance() {
        Asset asset = inStockAsset();
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));

        assertThrows(IllegalStateException.class, () -> service.release(asset.id(), actor, inScope));
    }

    @Test
    void releaseRefusesOutOfScope() {
        Asset asset = inMaintenanceAsset(Custody.NONE);
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));

        assertThrows(AccessDeniedException.class, () -> service.release(asset.id(), actor, outOfScope));
    }

    // -- retire ---------------------------------------------------------------------------------

    @Test
    void retireSetsRetiredAndKeepsCustodyAndHistory() {
        Asset asset = inStockAsset();
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));

        Asset result = service.retire(asset.id(), actor, inScope);

        assertEquals(InventoryState.RETIRED, result.inventoryState());
        assertEquals(asset.id(), result.id()); // same identity -- history preserved, not deleted
    }

    @Test
    void retireIsIdempotentOnAnAlreadyRetiredAsset() {
        Asset asset = retiredAsset();
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));

        Asset result = service.retire(asset.id(), actor, inScope);

        assertSame(asset, result);
        verify(assetRepository, never()).save(any());
    }

    @Test
    void retireRefusesAnIssuedAsset() {
        Asset asset = issuedAsset(UserId.random());
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));

        assertThrows(IllegalStateException.class, () -> service.retire(asset.id(), actor, inScope));
    }

    @Test
    void retireRefusesOutOfScope() {
        Asset asset = inStockAsset();
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));

        assertThrows(AccessDeniedException.class, () -> service.retire(asset.id(), actor, outOfScope));
    }

    // -- ground/release integration: the blocker becomes visible via MaintenanceQuery -----------

    @Test
    void groundThenReleaseClearsTheOpenBlockerRecordedInPersistence() {
        // Uses a hand-rolled in-memory MaintenanceRepositoryPort (rather than a mock) so that
        // ground() and release() genuinely observe the same store, proving the blocker really
        // opens and really closes -- not just that save() was called some number of times.
        InMemoryMaintenanceRepository realMaintenanceRepository = new InMemoryMaintenanceRepository();
        DefaultAssetCustodyService realService =
                new DefaultAssetCustodyService(assetRepository, realMaintenanceRepository, auditTrail);
        Asset asset = inStockAsset();
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));

        Asset grounded = realService.ground(asset.id(), MaintenanceKind.GROUNDING, "prop strike", actor, inScope);
        assertEquals(1, realMaintenanceRepository.findOpenByAsset(asset.id()).size());

        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(grounded));
        Asset released = realService.release(asset.id(), actor, inScope);

        assertEquals(InventoryState.IN_STOCK, released.inventoryState());
        assertEquals(0, realMaintenanceRepository.findOpenByAsset(asset.id()).size());
        List<MaintenanceRecord> history = realMaintenanceRepository.findByAsset(asset.id());
        assertEquals(1, history.size(),
                "the record itself is retained, closed, not deleted -- history stays intact");
        assertNotNull(history.get(0).closedAt());
    }

    /** Minimal in-memory {@link MaintenanceRepositoryPort} fake, used only for the one test above
     * that needs a real read-your-writes round trip rather than a stubbed mock. */
    private static final class InMemoryMaintenanceRepository implements MaintenanceRepositoryPort {
        private final Map<MaintenanceId, MaintenanceRecord> records = new java.util.LinkedHashMap<>();

        @Override
        public MaintenanceRecord save(MaintenanceRecord record) {
            records.put(record.id(), record);
            return record;
        }

        @Override
        public Optional<MaintenanceRecord> findById(MaintenanceId id) {
            return Optional.ofNullable(records.get(id));
        }

        @Override
        public List<MaintenanceRecord> findByAsset(AssetId assetId) {
            return records.values().stream().filter(r -> r.assetId().equals(assetId)).toList();
        }

        @Override
        public List<MaintenanceRecord> findOpenByAsset(AssetId assetId) {
            return records.values().stream().filter(r -> r.assetId().equals(assetId) && r.isOpen()).toList();
        }

        @Override
        public List<MaintenanceRecord> findOpen() {
            return records.values().stream().filter(MaintenanceRecord::isOpen).toList();
        }

        @Override
        public List<MaintenanceRecord> findRecentlyClosed(int limit) {
            return records.values().stream().filter(r -> !r.isOpen()).limit(limit).toList();
        }
    }
}

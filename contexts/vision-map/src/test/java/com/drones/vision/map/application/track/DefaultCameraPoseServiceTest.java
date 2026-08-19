package com.drones.vision.map.application.track;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.UserId;
import com.drones.vision.map.domain.model.CameraPose;
import com.drones.vision.map.domain.model.CameraPoseSource;
import com.drones.vision.map.domain.model.LayerId;
import com.drones.vision.map.domain.port.CameraPoseRepositoryPort;
import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.platform.AuditTrailPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultCameraPoseServiceTest {

    private static final GeoPosition POSITION = new GeoPosition(50.45, 30.52, null);

    private FakeCameraPoseRepositoryPort repository;
    private FakeAuditTrailPort auditTrail;
    private CameraPoseService service;

    private final AssetId assetId = AssetId.random();
    private final UserId actor = UserId.random();

    @BeforeEach
    void setUp() {
        repository = new FakeCameraPoseRepositoryPort();
        auditTrail = new FakeAuditTrailPort();
        service = new DefaultCameraPoseService(repository, auditTrail);
    }

    private static CameraPoseInput input() {
        return new CameraPoseInput(POSITION, 10.0, 45.0, 15.0, 60.0, LayerId.random(), CameraPoseSource.MANUAL, null);
    }

    // --- put: create ---------------------------------------------------------

    @Test
    void putCreatesANewPoseAndAuditsCreated() {
        CameraPose saved = service.put(assetId, input(), actor);

        assertEquals(assetId, saved.assetId());
        assertEquals(actor, saved.updatedBy());
        assertEquals(Optional.of(saved), repository.findByAssetId(assetId));

        assertEquals(1, auditTrail.entries.size());
        AuditEntry entry = auditTrail.entries.get(0);
        assertEquals(AuditAction.CREATED, entry.action());
        assertEquals(AuditTargetType.ASSET, entry.targetType());
        assertEquals(assetId.value().toString(), entry.targetId());
        assertEquals(actor, entry.actor());
    }

    // --- put: update -----------------------------------------------------------

    @Test
    void putReplacesAnExistingPoseAndAuditsUpdated() {
        service.put(assetId, input(), actor);
        auditTrail.entries.clear();

        CameraPoseInput secondInput =
                new CameraPoseInput(POSITION, 20.0, 90.0, 30.0, 80.0, LayerId.random(), CameraPoseSource.CALIBRATED, 1.5);
        CameraPose updated = service.put(assetId, secondInput, actor);

        assertEquals(20.0, updated.aglMeters(), 1e-9);
        assertEquals(1, repository.findAll().size());

        assertEquals(1, auditTrail.entries.size());
        assertEquals(AuditAction.UPDATED, auditTrail.entries.get(0).action());
    }

    @Test
    void putRejectsNullAssetId() {
        assertThrows(NullPointerException.class, () -> service.put(null, input(), actor));
    }

    @Test
    void putRejectsNullInput() {
        assertThrows(NullPointerException.class, () -> service.put(assetId, null, actor));
    }

    @Test
    void putRejectsNullActor() {
        assertThrows(NullPointerException.class, () -> service.put(assetId, input(), null));
    }

    // --- find / list -------------------------------------------------------------

    @Test
    void findReturnsEmptyForAnUnknownAsset() {
        assertEquals(Optional.empty(), service.find(assetId));
    }

    @Test
    void listReturnsEveryStoredPose() {
        service.put(assetId, input(), actor);
        service.put(AssetId.random(), input(), actor);

        assertEquals(2, service.list().size());
    }

    // --- delete ------------------------------------------------------------------

    @Test
    void deleteRemovesAnExistingPoseAndAudits() {
        service.put(assetId, input(), actor);
        auditTrail.entries.clear();

        service.delete(assetId, actor);

        assertEquals(Optional.empty(), repository.findByAssetId(assetId));
        assertEquals(1, auditTrail.entries.size());
        assertEquals(AuditAction.DELETED, auditTrail.entries.get(0).action());
    }

    @Test
    void deleteIsIdempotentAndAuditsNothingForANoOp() {
        service.delete(assetId, actor);

        assertTrue(auditTrail.entries.isEmpty());
        assertFalse(repository.findByAssetId(assetId).isPresent());
    }

    // --- Fakes ---------------------------------------------------------------------

    /** In-memory {@link CameraPoseRepositoryPort}. */
    static final class FakeCameraPoseRepositoryPort implements CameraPoseRepositoryPort {
        private final Map<AssetId, CameraPose> byAssetId = new ConcurrentHashMap<>();

        @Override
        public CameraPose save(CameraPose pose) {
            byAssetId.put(pose.assetId(), pose);
            return pose;
        }

        @Override
        public Optional<CameraPose> findByAssetId(AssetId assetId) {
            return Optional.ofNullable(byAssetId.get(assetId));
        }

        @Override
        public List<CameraPose> findAll() {
            return List.copyOf(byAssetId.values());
        }

        @Override
        public void deleteByAssetId(AssetId assetId) {
            byAssetId.remove(assetId);
        }
    }

    /** Capturing fake {@link AuditTrailPort}. */
    static final class FakeAuditTrailPort implements AuditTrailPort {
        final List<AuditEntry> entries = new ArrayList<>();

        @Override
        public AuditEntry record(AuditEntry entry) {
            entries.add(entry);
            return entry;
        }

        @Override
        public List<AuditEntry> findRecent(int limit) {
            return List.copyOf(entries);
        }

        @Override
        public List<AuditEntry> findByTarget(AuditTargetType targetType, String targetId, int limit) {
            return entries.stream().filter(e -> e.targetType() == targetType && e.targetId().equals(targetId)).toList();
        }

        @Override
        public List<AuditEntry> findByActor(UserId actor, int limit) {
            return entries.stream().filter(e -> e.actor().equals(actor)).toList();
        }
    }
}

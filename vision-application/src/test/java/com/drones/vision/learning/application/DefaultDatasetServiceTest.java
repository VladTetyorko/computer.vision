package com.drones.vision.learning.application;

import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.learning.domain.model.Dataset;
import com.drones.vision.learning.domain.model.DatasetId;
import com.drones.vision.learning.domain.model.DatasetStatus;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.learning.domain.port.DatasetRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.VisibilityScope;

class DefaultDatasetServiceTest {

    private FakeDatasetRepositoryPort datasetRepository;
    private FakeAuditTrailPort auditTrail;
    private DatasetService service;

    private final UserId actor = UserId.random();
    private final GroupId group = GroupId.random();
    private final Ownership ownership = new Ownership(actor, group);
    private final Instant fixedNow = Instant.parse("2026-08-01T10:00:00Z");

    @BeforeEach
    void setUp() {
        datasetRepository = new FakeDatasetRepositoryPort();
        auditTrail = new FakeAuditTrailPort();
        service = new DefaultDatasetService(datasetRepository, auditTrail, () -> fixedNow);
    }

    private static DatasetSpec spec() {
        return new DatasetSpec("Buildings", new CategoryId("building"), List.of("building", "tower"));
    }

    // --- create ------------------------------------------------------------

    @Test
    void createSavesAnOpenDatasetWithTheClockTimestampAndAuditsCreated() {
        Dataset created = service.create(spec(), ownership, actor, VisibilityScope.unbounded());

        assertEquals("Buildings", created.name());
        assertEquals(new CategoryId("building"), created.targetCategory());
        assertEquals(List.of("building", "tower"), created.classes());
        assertEquals(ownership, created.ownership());
        assertEquals(DatasetStatus.OPEN, created.status());
        assertEquals(fixedNow, created.createdAt());
        assertEquals(created, datasetRepository.findById(created.id()).orElseThrow());

        AuditEntry entry = onlyEntry();
        assertEquals(AuditAction.CREATED, entry.action());
        assertEquals(AuditTargetType.DATASET, entry.targetType());
        assertEquals(created.id().value().toString(), entry.targetId());
    }

    @Test
    void createDeniedForAPilotScopeAndAuditsTheDenialWithoutSaving() {
        VisibilityScope pilotScope = VisibilityScope.assignedAssets(Set.of());

        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> service.create(spec(), ownership, actor, pilotScope));
        assertTrue(ex.getMessage().toLowerCase().contains("not permitted"));
        assertEquals(List.of(), datasetRepository.findAll());

        AuditEntry entry = onlyEntry();
        assertEquals(AuditAction.UPDATED, entry.action());
        assertEquals("DENIED:out of scope", entry.details().get("result"));
    }

    @Test
    void createAllowedForAManagerGroupScope() {
        Dataset created = service.create(spec(), ownership, actor, VisibilityScope.groups(Set.of(group)));

        assertEquals(DatasetStatus.OPEN, created.status());
    }

    // --- list ----------------------------------------------------------------

    @Test
    void listReturnsEveryDatasetForAnUnboundedScope() {
        Dataset a = service.create(spec(), ownership, actor, VisibilityScope.unbounded());
        Dataset b = service.create(spec(), new Ownership(actor, GroupId.random()), actor, VisibilityScope.unbounded());

        List<Dataset> listed = service.list(actor, VisibilityScope.unbounded());

        assertEquals(Set.of(a.id(), b.id()), Set.of(listed.get(0).id(), listed.get(1).id()));
    }

    @Test
    void listFiltersToOnlyTheVisibleGroupForAManagerScope() {
        Dataset visible = service.create(spec(), ownership, actor, VisibilityScope.unbounded());
        Dataset other = service.create(spec(), new Ownership(actor, GroupId.random()), actor, VisibilityScope.unbounded());

        List<Dataset> listed = service.list(actor, VisibilityScope.groups(Set.of(group)));

        assertEquals(List.of(visible), listed);
        assertTrue(!listed.contains(other));
    }

    @Test
    void listIsEmptyForAPilotScope() {
        service.create(spec(), ownership, actor, VisibilityScope.unbounded());

        assertEquals(List.of(), service.list(actor, VisibilityScope.assignedAssets(Set.of())));
    }

    // --- get -----------------------------------------------------------------

    @Test
    void getReturnsTheDatasetWhenInScope() {
        Dataset created = service.create(spec(), ownership, actor, VisibilityScope.unbounded());

        assertEquals(created, service.get(created.id(), actor, VisibilityScope.groups(Set.of(group))));
    }

    @Test
    void getThrowsNoSuchElementForAnUnknownId() {
        assertThrows(NoSuchElementException.class,
                () -> service.get(DatasetId.random(), actor, VisibilityScope.unbounded()));
    }

    @Test
    void getThrowsAccessDeniedNotNoSuchElementForAnOutOfScopeDatasetAndAuditsIt() {
        Dataset created = service.create(spec(), ownership, actor, VisibilityScope.unbounded());
        auditTrail.entries.clear();

        assertThrows(AccessDeniedException.class,
                () -> service.get(created.id(), actor, VisibilityScope.groups(Set.of(GroupId.random()))));

        AuditEntry entry = onlyEntry();
        assertEquals("DENIED:out of scope", entry.details().get("result"));
    }

    // --- delete ----------------------------------------------------------------

    @Test
    void deleteRemovesTheDatasetAndAuditsDeleted() {
        Dataset created = service.create(spec(), ownership, actor, VisibilityScope.unbounded());
        auditTrail.entries.clear();

        service.delete(created.id(), actor, VisibilityScope.unbounded());

        assertEquals(Optional.empty(), datasetRepository.findById(created.id()));
        AuditEntry entry = onlyEntry();
        assertEquals(AuditAction.DELETED, entry.action());
    }

    @Test
    void deleteThrowsNoSuchElementForAnUnknownId() {
        assertThrows(NoSuchElementException.class,
                () -> service.delete(DatasetId.random(), actor, VisibilityScope.unbounded()));
    }

    @Test
    void deleteDeniedForAPilotScopeAndAuditsTheDenialWithoutDeleting() {
        Dataset created = service.create(spec(), ownership, actor, VisibilityScope.unbounded());
        auditTrail.entries.clear();

        assertThrows(AccessDeniedException.class,
                () -> service.delete(created.id(), actor, VisibilityScope.assignedAssets(Set.of())));

        assertTrue(datasetRepository.findById(created.id()).isPresent());
        AuditEntry entry = onlyEntry();
        assertEquals("DENIED:out of scope", entry.details().get("result"));
    }

    private AuditEntry onlyEntry() {
        assertEquals(1, auditTrail.entries.size(), "expected exactly one audit entry");
        return auditTrail.entries.get(0);
    }

    private static final class FakeDatasetRepositoryPort implements DatasetRepositoryPort {
        private final Map<DatasetId, Dataset> store = new LinkedHashMap<>();

        @Override
        public Dataset save(Dataset dataset) {
            store.put(dataset.id(), dataset);
            return dataset;
        }

        @Override
        public Optional<Dataset> findById(DatasetId id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<Dataset> findAll() {
            return List.copyOf(store.values());
        }

        @Override
        public void delete(DatasetId id) {
            store.remove(id);
        }
    }

    private static final class FakeAuditTrailPort implements AuditTrailPort {
        private final List<AuditEntry> entries = new ArrayList<>();

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
            return List.of();
        }

        @Override
        public List<AuditEntry> findByActor(UserId actorId, int limit) {
            return List.of();
        }
    }
}

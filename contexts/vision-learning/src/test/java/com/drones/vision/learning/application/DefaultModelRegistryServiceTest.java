package com.drones.vision.learning.application;

import com.drones.vision.kernel.UserId;
import com.drones.vision.learning.domain.model.CvModelRecord;
import com.drones.vision.learning.domain.model.MetricsKind;
import com.drones.vision.learning.domain.model.ModelAvailability;
import com.drones.vision.learning.domain.model.ModelMetrics;
import com.drones.vision.learning.domain.model.ModelProvenance;
import com.drones.vision.learning.domain.model.ModelRuntime;
import com.drones.vision.learning.domain.model.ModelStatus;
import com.drones.vision.learning.domain.model.ModelTaskType;
import com.drones.vision.learning.domain.port.CvModelRepositoryPort;
import com.drones.vision.learning.domain.port.ModelRegistryPort;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.platform.Authority;
import com.drones.vision.platform.Capability;
import com.drones.vision.platform.VisibilityScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultModelRegistryServiceTest {

    private FakeModelRegistryPort modelRegistry;
    private FakeCvModelRepositoryPort cvModelRepository;
    private ConfigModelCatalog configCatalog;
    private FakeAuditTrailPort auditTrail;
    private MutableClock clock;
    private DefaultModelRegistryService service;

    private final UserId actor = UserId.random();

    @BeforeEach
    void setUp() {
        modelRegistry = new FakeModelRegistryPort();
        cvModelRepository = new FakeCvModelRepositoryPort();
        configCatalog = new ConfigModelCatalog(List.of(configRow("yolo26n.pt")));
        auditTrail = new FakeAuditTrailPort();
        clock = new MutableClock(Instant.parse("2026-08-30T00:00:00Z"));
        service = new DefaultModelRegistryService(modelRegistry, cvModelRepository, configCatalog, auditTrail, clock);
    }

    // --- models(): the merge/availability matrix ------------------------------------------------

    @Test
    void modelsMarksARowPresentWhenTheWorkerReportsIt() {
        cvModelRepository.rows.add(row("yolo26n.pt", "latest", ModelStatus.LIVE, actor, clock.instant));
        modelRegistry.models = List.of(new ModelRef("yolo26n.pt", "latest"));
        modelRegistry.active = new ModelRef("yolo26n.pt", "latest");

        CvModelCatalog catalog = service.models();

        assertEquals(CatalogSource.REGISTRY, catalog.source());
        CvModelView view = onlyView(catalog);
        assertEquals(ModelAvailability.PRESENT, view.availability());
        assertEquals(ModelStatus.LIVE, view.status());
        assertTrue(auditTrail.entries.isEmpty(), "a read must never audit");
    }

    @Test
    void modelsMarksARowMissingOnWorkerWhenTheWorkerDoesNotReportIt() {
        cvModelRepository.rows.add(row("orion12l.pt", "latest", ModelStatus.DRAFT, null, null));
        modelRegistry.models = List.of(); // the worker reports nothing

        CvModelCatalog catalog = service.models();

        CvModelView view = onlyView(catalog);
        assertEquals(ModelAvailability.MISSING_ON_WORKER, view.availability());
        assertEquals(CatalogSource.REGISTRY, catalog.source());
    }

    @Test
    void modelsSynthesizesARowLessViewForAWorkerModelWithNoPlatformRow() {
        modelRegistry.models = List.of(new ModelRef("yolo11n.pt", "latest"));
        modelRegistry.active = null;

        CvModelCatalog catalog = service.models();

        CvModelView view = onlyView(catalog);
        assertEquals("yolo11n.pt", view.modelId());
        assertEquals(ModelAvailability.PRESENT, view.availability());
        assertEquals(ModelStatus.DRAFT, view.status(), "no active stage -> honest DRAFT fallback, not a guess");
    }

    @Test
    void modelsSynthesizesALiveStatusWhenTheWorkerMarksItActive() {
        ModelRef ref = new ModelRef("yolo11n.pt", "latest");
        modelRegistry.models = List.of(ref);
        modelRegistry.active = ref;

        CvModelView view = onlyView(service.models());

        assertEquals(ModelStatus.LIVE, view.status());
    }

    @Test
    void modelsListsARowMissingOnWorkerAndASynthesizedRowTogether() {
        cvModelRepository.rows.add(row("orion12l.pt", "latest", ModelStatus.DRAFT, null, null));
        modelRegistry.models = List.of(new ModelRef("yolo11n.pt", "latest"));

        CvModelCatalog catalog = service.models();

        assertEquals(2, catalog.models().size());
        assertTrue(catalog.models().stream()
                .anyMatch(v -> v.modelId().equals("orion12l.pt") && v.availability() == ModelAvailability.MISSING_ON_WORKER));
        assertTrue(catalog.models().stream()
                .anyMatch(v -> v.modelId().equals("yolo11n.pt") && v.availability() == ModelAvailability.PRESENT));
    }

    @Test
    void modelsFallsBackToTheConfigCatalogWhenTheWorkerIsUnreachableAndNeverThrows() {
        modelRegistry.unreachable = true;

        CvModelCatalog catalog = service.models();

        assertEquals(CatalogSource.CONFIG, catalog.source());
        CvModelView view = onlyView(catalog);
        assertEquals("yolo26n.pt", view.modelId());
        assertEquals(ModelAvailability.PRESENT, view.availability());
        assertTrue(auditTrail.entries.isEmpty());
    }

    // --- promote -----------------------------------------------------------------------------

    @Test
    void promoteDeniedForAPilotScopeAndAuditsTheDenialWithoutCallingThePort() {
        Authority pilotScope = new Authority(VisibilityScope.assignedAssets(Set.of()), Set.of());

        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> service.promote("yolo26n.pt", "latest", actor, pilotScope));
        assertTrue(ex.getMessage().toLowerCase().contains("not permitted"));

        assertNull(modelRegistry.lastPromoted);
        AuditEntry entry = onlyEntry();
        assertEquals("DENIED:out of scope", entry.details().get("result"));
    }

    @Test
    void promoteDeniedForAManagerScopeAndAuditsTheDenialWithoutCallingThePort() {
        Authority managerScope = new Authority(VisibilityScope.groups(Set.of()), Set.of(Capability.MANAGE_ORG));

        assertThrows(AccessDeniedException.class,
                () -> service.promote("yolo26n.pt", "latest", actor, managerScope));

        assertNull(modelRegistry.lastPromoted);
        assertEquals("DENIED:out of scope", onlyEntry().details().get("result"));
    }

    @Test
    void promoteRejectsABlankModelId() {
        assertThrows(IllegalArgumentException.class,
                () -> service.promote("", "latest", actor, Authority.full()));
    }

    @Test
    void promoteSetsLiveAndDemotesThePreviousLiveRow() {
        cvModelRepository.rows.add(row("yolo26n.pt", "latest", ModelStatus.LIVE, actor,
                Instant.parse("2026-08-01T00:00:00Z")));
        cvModelRepository.rows.add(row("yolo11n.pt", "latest", ModelStatus.DRAFT, null, null));

        PromotionResult result = service.promote("yolo11n.pt", "latest", actor, Authority.full());

        assertEquals("yolo11n.pt", result.modelId());
        assertEquals(ModelStatus.LIVE, result.status());
        assertEquals("yolo26n.pt", result.previousModelId());
        assertEquals("latest", result.previousVersion());

        CvModelRecord promoted = cvModelRepository.get("yolo11n.pt", "latest");
        assertEquals(ModelStatus.LIVE, promoted.status());
        assertEquals(actor, promoted.promotedBy());
        assertEquals(clock.instant, promoted.promotedAt());

        CvModelRecord demoted = cvModelRepository.get("yolo26n.pt", "latest");
        assertEquals(ModelStatus.RETIRED, demoted.status());
        assertEquals(actor, demoted.promotedBy(), "retire() keeps the promotion stamp so rollback can find it");

        assertEquals(new ModelRef("yolo11n.pt", "latest"), modelRegistry.lastPromoted);
        assertEquals("PROMOTED", onlyEntry().details().get("result"));
    }

    @Test
    void promoteWithNoPreviousLiveLeavesPreviousFieldsNull() {
        cvModelRepository.rows.add(row("yolo26n.pt", "latest", ModelStatus.DRAFT, null, null));

        PromotionResult result = service.promote("yolo26n.pt", "latest", actor, Authority.full());

        assertNull(result.previousModelId());
        assertNull(result.previousVersion());
    }

    @Test
    void promoteSynthesizesARowForAWorkerOnlyModelWithNoPlatformRowYet() {
        PromotionResult result = service.promote("orion12l.pt", "latest", actor, Authority.full());

        assertEquals(ModelStatus.LIVE, result.status());
        CvModelRecord saved = cvModelRepository.get("orion12l.pt", "latest");
        assertEquals(ModelStatus.LIVE, saved.status());
        assertEquals("orion12l.pt", saved.displayName(), "row synthesized with no known display name");
    }

    @Test
    void promotePropagatesAndAuditsARefusalFromThePortAndSavesNothing() {
        modelRegistry.promoteFailure = new IllegalStateException("unknown id");
        cvModelRepository.rows.add(row("yolo26n.pt", "latest", ModelStatus.LIVE, actor, clock.instant));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.promote("yolo11n.pt", "latest", actor, Authority.full()));
        assertTrue(ex.getMessage().contains("unknown id"));

        assertTrue(onlyEntry().details().get("result").startsWith("REFUSED:"));
        assertEquals(ModelStatus.LIVE, cvModelRepository.get("yolo26n.pt", "latest").status(),
                "a refused promotion must not demote the still-live row");
        assertNull(cvModelRepository.findByIdAndVersion("yolo11n.pt", "latest").orElse(null));
    }

    // --- rollback ----------------------------------------------------------------------------

    @Test
    void rollbackDeniedForAManagerScope() {
        assertThrows(AccessDeniedException.class,
                () -> service.rollback(actor, new Authority(VisibilityScope.groups(Set.of()), Set.of(Capability.MANAGE_ORG))));
        assertEquals("DENIED:out of scope", onlyEntry().details().get("result"));
    }

    @Test
    void rollbackRefusesWhenThereIsNoPreviousModel() {
        cvModelRepository.rows.add(row("yolo26n.pt", "latest", ModelStatus.LIVE, actor, clock.instant));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.rollback(actor, Authority.full()));
        assertTrue(ex.getMessage().contains("No previous model"));
        assertEquals("REFUSED:no previous model", onlyEntry().details().get("result"));
        assertNull(modelRegistry.lastPromoted);
    }

    @Test
    void rollbackRestoresTheMostRecentlyRetiredModelAndDemotesTheCurrentOne() {
        cvModelRepository.rows.add(row("yolo26n.pt", "latest", ModelStatus.LIVE, actor,
                Instant.parse("2026-08-20T00:00:00Z")));
        cvModelRepository.rows.add(row("yolo11n.pt", "latest", ModelStatus.RETIRED, actor,
                Instant.parse("2026-08-10T00:00:00Z")));
        cvModelRepository.rows.add(row("orion12l.pt", "latest", ModelStatus.RETIRED, actor,
                Instant.parse("2026-08-05T00:00:00Z")));

        PromotionResult result = service.rollback(actor, Authority.full());

        assertEquals("yolo11n.pt", result.modelId(), "the most recently retired-by-promotion row wins");
        assertEquals(ModelStatus.LIVE, result.status());
        assertEquals("yolo26n.pt", result.previousModelId());

        assertEquals(ModelStatus.LIVE, cvModelRepository.get("yolo11n.pt", "latest").status());
        assertEquals(ModelStatus.RETIRED, cvModelRepository.get("yolo26n.pt", "latest").status());
        assertEquals(ModelStatus.RETIRED, cvModelRepository.get("orion12l.pt", "latest").status(),
                "the older retired row is left alone");
        assertEquals(new ModelRef("yolo11n.pt", "latest"), modelRegistry.lastPromoted);
        assertEquals("ROLLED_BACK", onlyEntry().details().get("result"));
    }

    @Test
    void rollbackPropagatesAndAuditsARefusalFromThePort() {
        cvModelRepository.rows.add(row("yolo26n.pt", "latest", ModelStatus.LIVE, actor, clock.instant));
        cvModelRepository.rows.add(row("yolo11n.pt", "latest", ModelStatus.RETIRED, actor,
                Instant.parse("2026-08-01T00:00:00Z")));
        modelRegistry.promoteFailure = new IllegalStateException("no artifact on disk");

        assertThrows(IllegalStateException.class, () -> service.rollback(actor, Authority.full()));

        assertTrue(onlyEntry().details().get("result").startsWith("REFUSED:"));
        assertEquals(ModelStatus.LIVE, cvModelRepository.get("yolo26n.pt", "latest").status(),
                "a refused rollback must not demote the still-live row");
    }

    // --- helpers -------------------------------------------------------------------------------

    private static CvModelView onlyView(CvModelCatalog catalog) {
        assertEquals(1, catalog.models().size());
        return catalog.models().get(0);
    }

    private AuditEntry onlyEntry() {
        assertEquals(1, auditTrail.entries.size(), "expected exactly one audit entry");
        return auditTrail.entries.get(0);
    }

    private static CvModelRecord row(String modelId, String version, ModelStatus status, UserId promotedBy,
                                      Instant promotedAt) {
        return new CvModelRecord(modelId, version, modelId, "general", false, List.of(), ModelTaskType.DETECT,
                ModelRuntime.PYTORCH, List.of(), status, new ModelMetrics(0.7, MetricsKind.TRAINING),
                ModelProvenance.none(), promotedBy, promotedAt, Instant.parse("2026-08-01T00:00:00Z"));
    }

    private static CvModelRecord configRow(String modelId) {
        return row(modelId, "latest", ModelStatus.DRAFT, null, null);
    }

    private static final class FakeModelRegistryPort implements ModelRegistryPort {
        private List<ModelRef> models = List.of();
        private ModelRef active;
        private boolean unreachable;
        private RuntimeException promoteFailure;
        private ModelRef lastPromoted;

        @Override
        public List<ModelRef> models() {
            if (unreachable) {
                throw new RuntimeException("cv-service unreachable");
            }
            return models;
        }

        @Override
        public Optional<ModelRef> active() {
            if (unreachable) {
                throw new RuntimeException("cv-service unreachable");
            }
            return Optional.ofNullable(active);
        }

        @Override
        public void promote(ModelRef ref) {
            lastPromoted = ref;
            if (promoteFailure != null) {
                throw promoteFailure;
            }
        }
    }

    private static final class FakeCvModelRepositoryPort implements CvModelRepositoryPort {
        private final List<CvModelRecord> rows = new ArrayList<>();

        @Override
        public Optional<CvModelRecord> findByIdAndVersion(String modelId, String version) {
            return rows.stream()
                    .filter(r -> r.modelId().equals(modelId) && r.version().equals(version))
                    .findFirst();
        }

        @Override
        public List<CvModelRecord> findAll() {
            return List.copyOf(rows);
        }

        @Override
        public Optional<CvModelRecord> findLive() {
            return rows.stream().filter(r -> r.status() == ModelStatus.LIVE).findFirst();
        }

        @Override
        public CvModelRecord save(CvModelRecord model) {
            rows.removeIf(r -> r.modelId().equals(model.modelId()) && r.version().equals(model.version()));
            rows.add(model);
            return model;
        }

        private CvModelRecord get(String modelId, String version) {
            return findByIdAndVersion(modelId, version).orElseThrow();
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

    private static final class MutableClock implements java.util.function.Supplier<Instant> {
        private final Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public Instant get() {
            return instant;
        }
    }
}

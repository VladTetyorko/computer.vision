package com.drones.vision.learning.application;

import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.learning.domain.port.ModelRegistryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.VisibilityScope;

class DefaultModelRegistryServiceTest {

    private FakeModelRegistryPort modelRegistry;
    private FakeAuditTrailPort auditTrail;
    private ModelRegistryService service;

    private final UserId actor = UserId.random();
    private final ModelRef modelA = new ModelRef("yolo26n", "1");
    private final ModelRef modelB = new ModelRef("yolo26n", "2");

    @BeforeEach
    void setUp() {
        modelRegistry = new FakeModelRegistryPort();
        auditTrail = new FakeAuditTrailPort();
        service = new DefaultModelRegistryService(modelRegistry, auditTrail);
    }

    // --- models ----------------------------------------------------------------

    @Test
    void modelsMarksTheActiveOneAndLeavesEveryOtherFalse() {
        modelRegistry.models = List.of(modelA, modelB);
        modelRegistry.active = modelB;

        List<RegisteredModel> models = service.models();

        assertEquals(List.of(new RegisteredModel(modelA, false), new RegisteredModel(modelB, true)), models);
        assertTrue(auditTrail.entries.isEmpty(), "a read must never audit");
    }

    @Test
    void modelsMarksEveryModelInactiveWhenTheRegistryHasNoActiveModel() {
        modelRegistry.models = List.of(modelA, modelB);
        modelRegistry.active = null;

        List<RegisteredModel> models = service.models();

        assertTrue(models.stream().noneMatch(RegisteredModel::active));
    }

    // --- promote ----------------------------------------------------------------

    @Test
    void promoteDeniedForAPilotScopeAndAuditsTheDenialWithoutCallingThePort() {
        VisibilityScope pilotScope = VisibilityScope.assignedAssets(Set.of());

        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> service.promote(modelA, actor, pilotScope));
        assertTrue(ex.getMessage().toLowerCase().contains("not permitted"));

        assertNull(modelRegistry.lastPromoted);
        AuditEntry entry = onlyEntry();
        assertEquals(AuditAction.UPDATED, entry.action());
        assertEquals(AuditTargetType.MODEL, entry.targetType());
        assertEquals("DENIED:out of scope", entry.details().get("result"));
    }

    @Test
    void promoteSucceedsForAManagerScopeAndAuditsPromoted() {
        service.promote(modelA, actor, VisibilityScope.groups(Set.of()));

        assertEquals(modelA, modelRegistry.lastPromoted);
        AuditEntry entry = onlyEntry();
        assertEquals(AuditAction.UPDATED, entry.action());
        assertEquals(AuditTargetType.MODEL, entry.targetType());
        assertEquals(modelA.id() + ":" + modelA.version(), entry.targetId());
        assertEquals("PROMOTED", entry.details().get("result"));
        assertEquals(modelA.id(), entry.details().get("modelId"));
        assertEquals(modelA.version(), entry.details().get("modelVersion"));
    }

    @Test
    void promotePropagatesAndAuditsARefusalFromThePort() {
        modelRegistry.promoteFailure =
                new IllegalStateException("cv-service refused to promote model " + modelA + ": unknown id");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.promote(modelA, actor, VisibilityScope.unbounded()));
        assertTrue(ex.getMessage().contains("unknown id"));

        assertEquals(modelA, modelRegistry.lastPromoted, "the port must still have been called");
        AuditEntry entry = onlyEntry();
        assertTrue(entry.details().get("result").startsWith("REFUSED:"));
        assertTrue(entry.details().get("result").contains("unknown id"));
    }

    private AuditEntry onlyEntry() {
        assertEquals(1, auditTrail.entries.size(), "expected exactly one audit entry");
        return auditTrail.entries.get(0);
    }

    private static final class FakeModelRegistryPort implements ModelRegistryPort {
        private List<ModelRef> models = List.of();
        private ModelRef active;
        private RuntimeException promoteFailure;
        private ModelRef lastPromoted;

        @Override
        public List<ModelRef> models() {
            return models;
        }

        @Override
        public Optional<ModelRef> active() {
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

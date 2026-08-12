package com.drones.vision.learning.application;

import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.learning.domain.model.Dataset;
import com.drones.vision.learning.domain.model.DatasetId;
import com.drones.vision.learning.domain.model.DatasetStatus;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.learning.domain.port.DatasetRepositoryPort;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Supplier;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.VisibilityScope;

/**
 * The one implementation of {@link DatasetService}.
 *
 * <h2>Scope gate</h2>
 * {@link #create}/{@link #delete} require {@link VisibilityScope#canManageOrg()} — any
 * manager/admin, not further restricted to the dataset's own owning group (docs/plans/done/CV-TRAINING-PLAN.md
 * Open Questions §4: "create/delete = canManageOrg"). {@link #get} 403s (not the usual hiding 404)
 * when the dataset exists but is outside a {@link VisibilityScope.Kind#GROUPS} scope's visible
 * subtree, per this feature's own frozen contract. Every denial here — create, delete, and get
 * alike — is audited ({@code DENIED:out of scope}), per docs/plans/done/CV-TRAINING-PLAN.md §2's own "Scope
 * gate" paragraph ("dataset ops ... + audit DENIED"), reusing {@code
 * DefaultFlightCommandService}'s "audit every attempt, success or refusal" idiom. {@link #list}
 * never throws (it silently filters), so it has nothing to audit.
 *
 * <h2>Threading</h2>
 * Holds no mutable state — all shared state is reached through the injected collaborators.
 */
public final class DefaultDatasetService implements DatasetService {

    private static final String ACTION_CREATE = "CREATE";
    private static final String ACTION_DELETE = "DELETE";
    private static final String ACTION_GET = "GET";
    private static final String DENIED_OUT_OF_SCOPE = "DENIED:out of scope";
    private static final String ATTR_DATASET_ID = "datasetId";
    private static final String ATTR_ACTION = "action";
    private static final String ATTR_RESULT = "result";

    private final DatasetRepositoryPort datasetRepository;
    private final AuditTrailPort auditTrail;
    private final Supplier<Instant> clock;

    public DefaultDatasetService(DatasetRepositoryPort datasetRepository, AuditTrailPort auditTrail) {
        this(datasetRepository, auditTrail, Instant::now);
    }

    /**
     * Test seam: same as the 2-argument constructor, with an injectable clock so {@link
     * Dataset#createdAt()} is deterministic in tests instead of depending on wall-clock time.
     */
    DefaultDatasetService(DatasetRepositoryPort datasetRepository, AuditTrailPort auditTrail,
                           Supplier<Instant> clock) {
        this.datasetRepository = Objects.requireNonNull(datasetRepository, "datasetRepository must not be null");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Dataset create(DatasetSpec spec, Ownership ownership, UserId actor, VisibilityScope scope) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(ownership, "ownership must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");

        DatasetId id = DatasetId.random();
        if (!scope.canManageOrg()) {
            auditDenied(actor, id, ACTION_CREATE, "Denied creating dataset '" + spec.name() + "': out of scope");
            throw new AccessDeniedException("Not permitted to create datasets");
        }

        Dataset dataset = new Dataset(id, spec.name(), spec.targetCategory(), spec.classes(), ownership,
                DatasetStatus.OPEN, clock.get());
        Dataset saved = datasetRepository.save(dataset);
        audit(actor, id, AuditAction.CREATED, ACTION_CREATE, "CREATED", "Created dataset '" + spec.name() + "'");
        return saved;
    }

    @Override
    public List<Dataset> list(UserId actor, VisibilityScope scope) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        return datasetRepository.findAll().stream()
                .filter(dataset -> scope.includesGroup(dataset.ownership().groupId()))
                .toList();
    }

    @Override
    public Dataset get(DatasetId id, UserId actor, VisibilityScope scope) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Dataset dataset = require(id);
        if (!scope.includesGroup(dataset.ownership().groupId())) {
            auditDenied(actor, id, ACTION_GET, "Denied reading dataset " + id.value() + ": out of scope");
            throw new AccessDeniedException("Dataset " + id.value() + " is outside your scope");
        }
        return dataset;
    }

    @Override
    public void delete(DatasetId id, UserId actor, VisibilityScope scope) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Dataset dataset = require(id);
        if (!scope.canManageOrg()) {
            auditDenied(actor, id, ACTION_DELETE, "Denied deleting dataset " + id.value() + ": out of scope");
            throw new AccessDeniedException("Not permitted to delete dataset " + id.value());
        }
        datasetRepository.delete(id);
        audit(actor, id, AuditAction.DELETED, ACTION_DELETE, "DELETED", "Deleted dataset '" + dataset.name() + "'");
    }

    private Dataset require(DatasetId id) {
        return datasetRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Unknown dataset: " + id.value()));
    }

    private void auditDenied(UserId actor, DatasetId id, String action, String summary) {
        audit(actor, id, AuditAction.UPDATED, action, DENIED_OUT_OF_SCOPE, summary);
    }

    private void audit(UserId actor, DatasetId id, AuditAction auditAction, String action, String result,
                        String summary) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(ATTR_DATASET_ID, id.value().toString());
        attributes.put(ATTR_ACTION, action);
        attributes.put(ATTR_RESULT, result);
        auditTrail.record(
                AuditEntry.of(actor, auditAction, AuditTargetType.DATASET, id.value().toString(), summary, attributes));
    }
}

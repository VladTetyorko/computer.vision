package com.drones.vision.learning.application;

import com.drones.vision.kernel.UserId;
import com.drones.vision.learning.domain.model.CvModelRecord;
import com.drones.vision.learning.domain.model.ModelAvailability;
import com.drones.vision.learning.domain.model.ModelProvenance;
import com.drones.vision.learning.domain.model.ModelStatus;
import com.drones.vision.learning.domain.port.CvModelRepositoryPort;
import com.drones.vision.learning.domain.port.ModelRegistryPort;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.platform.VisibilityScope;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The one implementation of {@link ModelRegistryService}.
 *
 * <h2>The merge ({@link #models()})</h2>
 * Joins {@link CvModelRepositoryPort#findAll()} (platform governance rows) with {@link
 * ModelRegistryPort#models()}/{@link ModelRegistryPort#active()} (the connected worker's own
 * reported roster) by (modelId, version): a row present in both gets the worker's {@link
 * ModelAvailability#PRESENT}; a row absent from the worker's roster is still listed, marked {@link
 * ModelAvailability#MISSING_ON_WORKER} (honesty rule 4 — never silently substitute); a worker model
 * with no platform row at all gets a best-effort {@link CvModelView#synthesize synthesized} view
 * (there is nothing else to show for a checkpoint that has never trained or promoted through this
 * platform's loop — true of every built-in on a fresh deployment, since {@code cv_models} seeds no
 * rows). If the worker cannot be reached at all, {@link #models()} falls back to {@link
 * #configCatalog} instead of throwing (docs/plans/active/CV-SETTINGS-PLAN.md §8 OQ5) — every row
 * reported {@link ModelAvailability#PRESENT} (there is nothing to check it against), with {@link
 * CatalogSource#CONFIG} itself carrying the "not verified live" honesty signal.
 *
 * <h2>Promote/rollback</h2>
 * {@link #promote}/{@link #rollback} both require {@link VisibilityScope#canAdminister()} — ADMIN
 * only (docs/plans/done/OPS-UX-PLAN.md §1). {@link #promote} resolves (or, for a worker-only model
 * with no row yet, synthesizes — same defaults {@link CvModelView#synthesize} uses, see that
 * method's own javadoc) the target row, calls the worker's own {@link ModelRegistryPort#promote}
 * first (so a refusal leaves no platform row mutated), then persists the target as {@code LIVE} and
 * whichever row {@link CvModelRepositoryPort#findLive()} reported before this call as {@code
 * RETIRED}. {@link #rollback} finds the most-recently-promoted {@code RETIRED} row (by {@link
 * CvModelRecord#promotedAt()} — {@link CvModelRecord#retire()} keeps that stamp, which is exactly
 * what lets rollback find it again, per docs/plans/active/CV-SETTINGS-CONTEXT.md's W4-domain
 * handoff), refuses with {@link IllegalStateException} when none exists, else promotes it back and
 * retires whatever is currently {@code LIVE}.
 *
 * <h2>Audit</h2>
 * Every {@link #promote}/{@link #rollback} attempt writes exactly one {@link AuditEntry} against
 * {@link AuditTargetType#MODEL} — a scope denial ({@code DENIED:out of scope}), cv-service's own
 * refusal ({@code REFUSED:<message>}), rollback's own "nothing to restore" refusal ({@code
 * REFUSED:no previous model}), and success ({@code PROMOTED}/{@code ROLLED_BACK}) alike, reusing
 * {@code DefaultFlightCommandService}'s "audit every attempt, success or refusal" idiom. {@link
 * AuditAction} has no dedicated "promoted"/"rolled back" value; {@link AuditAction#UPDATED} is the
 * closest existing fit. {@link #models()} never throws and is not audited (a read).
 *
 * <h2>Threading</h2>
 * Holds no mutable state — all shared state is reached through the injected collaborators.
 */
public final class DefaultModelRegistryService implements ModelRegistryService {

    private static final String ATTR_MODEL_ID = "modelId";
    private static final String ATTR_MODEL_VERSION = "modelVersion";
    private static final String ATTR_RESULT = "result";
    private static final String DENIED_OUT_OF_SCOPE = "DENIED:out of scope";
    private static final String REFUSED_PREFIX = "REFUSED:";
    private static final String REFUSED_NO_PREVIOUS = "REFUSED:no previous model";
    private static final String RESULT_PROMOTED = "PROMOTED";
    private static final String RESULT_ROLLED_BACK = "ROLLED_BACK";
    private static final String ROLLBACK_TARGET_ID = "rollback";

    private final ModelRegistryPort modelRegistryPort;
    private final CvModelRepositoryPort cvModelRepositoryPort;
    private final ConfigModelCatalog configCatalog;
    private final AuditTrailPort auditTrail;
    private final Supplier<Instant> clock;

    public DefaultModelRegistryService(ModelRegistryPort modelRegistryPort,
                                        CvModelRepositoryPort cvModelRepositoryPort,
                                        ConfigModelCatalog configCatalog, AuditTrailPort auditTrail) {
        this(modelRegistryPort, cvModelRepositoryPort, configCatalog, auditTrail, Instant::now);
    }

    /** Test seam: same as the 4-argument constructor, with an injectable clock. */
    DefaultModelRegistryService(ModelRegistryPort modelRegistryPort, CvModelRepositoryPort cvModelRepositoryPort,
                                 ConfigModelCatalog configCatalog, AuditTrailPort auditTrail,
                                 Supplier<Instant> clock) {
        this.modelRegistryPort = Objects.requireNonNull(modelRegistryPort, "modelRegistryPort must not be null");
        this.cvModelRepositoryPort =
                Objects.requireNonNull(cvModelRepositoryPort, "cvModelRepositoryPort must not be null");
        this.configCatalog = Objects.requireNonNull(configCatalog, "configCatalog must not be null");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public CvModelCatalog models() {
        List<ModelRef> workerModels;
        Optional<ModelRef> workerActive;
        try {
            workerModels = modelRegistryPort.models();
            workerActive = modelRegistryPort.active();
        } catch (RuntimeException e) {
            return configCatalogView();
        }
        return mergedView(workerModels, workerActive);
    }

    private CvModelCatalog configCatalogView() {
        List<CvModelView> views = configCatalog.models().stream()
                .map(record -> CvModelView.of(record, ModelAvailability.PRESENT))
                .toList();
        return new CvModelCatalog(views, CatalogSource.CONFIG);
    }

    private CvModelCatalog mergedView(List<ModelRef> workerModels, Optional<ModelRef> workerActive) {
        Set<ModelRef> workerRefs = Set.copyOf(workerModels);
        List<CvModelRecord> rows = cvModelRepositoryPort.findAll();

        List<CvModelView> views = new ArrayList<>(rows.size() + workerModels.size());
        Set<ModelRef> rowRefs = new LinkedHashSet<>();
        for (CvModelRecord row : rows) {
            ModelRef ref = new ModelRef(row.modelId(), row.version());
            rowRefs.add(ref);
            ModelAvailability availability =
                    workerRefs.contains(ref) ? ModelAvailability.PRESENT : ModelAvailability.MISSING_ON_WORKER;
            views.add(CvModelView.of(row, availability));
        }
        for (ModelRef ref : workerModels) {
            if (!rowRefs.contains(ref)) {
                boolean workerReportsLive = workerActive.isPresent() && workerActive.get().equals(ref);
                views.add(CvModelView.synthesize(ref, workerReportsLive));
            }
        }
        return new CvModelCatalog(List.copyOf(views), CatalogSource.REGISTRY);
    }

    @Override
    public PromotionResult promote(String modelId, String version, UserId actor, VisibilityScope scope) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        ModelRef ref = new ModelRef(modelId, version);

        if (!scope.canAdminister()) {
            auditModel(actor, ref, DENIED_OUT_OF_SCOPE);
            throw new AccessDeniedException("Not permitted to promote models");
        }

        Optional<CvModelRecord> currentLive = cvModelRepositoryPort.findLive();
        boolean alreadyLive = currentLive.isPresent()
                && currentLive.get().modelId().equals(modelId) && currentLive.get().version().equals(version);

        try {
            modelRegistryPort.promote(ref);
        } catch (IllegalStateException e) {
            auditModel(actor, ref, REFUSED_PREFIX + e.getMessage());
            throw e;
        }

        Instant now = clock.get();
        CvModelRecord target = cvModelRepositoryPort.findByIdAndVersion(modelId, version)
                .orElseGet(() -> synthesizeRow(ref, now));
        cvModelRepositoryPort.save(target.promote(actor, now));

        String previousModelId = null;
        String previousVersion = null;
        if (currentLive.isPresent() && !alreadyLive) {
            CvModelRecord previous = currentLive.get();
            cvModelRepositoryPort.save(previous.retire());
            previousModelId = previous.modelId();
            previousVersion = previous.version();
        }

        auditModel(actor, ref, RESULT_PROMOTED);
        return new PromotionResult(modelId, version, ModelStatus.LIVE, previousModelId, previousVersion);
    }

    @Override
    public PromotionResult rollback(UserId actor, VisibilityScope scope) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");

        if (!scope.canAdminister()) {
            auditRollback(actor, null, DENIED_OUT_OF_SCOPE);
            throw new AccessDeniedException("Not permitted to roll back models");
        }

        Optional<CvModelRecord> previous = cvModelRepositoryPort.findAll().stream()
                .filter(row -> row.status() == ModelStatus.RETIRED)
                .max(Comparator.comparing(CvModelRecord::promotedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
        if (previous.isEmpty()) {
            auditRollback(actor, null, REFUSED_NO_PREVIOUS);
            throw new IllegalStateException("No previous model to roll back to");
        }
        CvModelRecord previousRow = previous.get();
        ModelRef previousRef = new ModelRef(previousRow.modelId(), previousRow.version());

        try {
            modelRegistryPort.promote(previousRef);
        } catch (IllegalStateException e) {
            auditRollback(actor, previousRef, REFUSED_PREFIX + e.getMessage());
            throw e;
        }

        Instant now = clock.get();
        Optional<CvModelRecord> currentLive = cvModelRepositoryPort.findLive();
        String demotedModelId = null;
        String demotedVersion = null;
        if (currentLive.isPresent()) {
            CvModelRecord current = currentLive.get();
            cvModelRepositoryPort.save(current.retire());
            demotedModelId = current.modelId();
            demotedVersion = current.version();
        }
        cvModelRepositoryPort.save(previousRow.promote(actor, now));

        auditRollback(actor, previousRef, RESULT_ROLLED_BACK);
        return new PromotionResult(previousRow.modelId(), previousRow.version(), ModelStatus.LIVE, demotedModelId,
                demotedVersion);
    }

    /**
     * A minimal, best-effort row for a model that has no {@link CvModelRecord} yet — see {@link
     * CvModelView#synthesize} for why the two must use the same defaults.
     */
    private static CvModelRecord synthesizeRow(ModelRef ref, Instant now) {
        return new CvModelRecord(ref.id(), ref.version(), ref.id(), CvModelView.SYNTHESIZED_KIND, false, List.of(),
                CvModelView.SYNTHESIZED_TASK_TYPE, CvModelView.SYNTHESIZED_RUNTIME, List.of(), ModelStatus.DRAFT,
                null, ModelProvenance.none(), null, null, now);
    }

    private void auditModel(UserId actor, ModelRef ref, String result) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(ATTR_MODEL_ID, ref.id());
        attributes.put(ATTR_MODEL_VERSION, ref.version());
        attributes.put(ATTR_RESULT, result);
        String targetId = ref.id() + ":" + ref.version();
        auditTrail.record(AuditEntry.of(actor, AuditAction.UPDATED, AuditTargetType.MODEL, targetId,
                "Promote model " + targetId, attributes));
    }

    private void auditRollback(UserId actor, ModelRef ref, String result) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(ATTR_RESULT, result);
        String targetId = ROLLBACK_TARGET_ID;
        if (ref != null) {
            attributes.put(ATTR_MODEL_ID, ref.id());
            attributes.put(ATTR_MODEL_VERSION, ref.version());
            targetId = ref.id() + ":" + ref.version();
        }
        auditTrail.record(AuditEntry.of(actor, AuditAction.UPDATED, AuditTargetType.MODEL, targetId,
                "Roll back model registry", attributes));
    }
}

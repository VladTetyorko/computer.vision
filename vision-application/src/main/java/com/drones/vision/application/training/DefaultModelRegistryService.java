package com.drones.vision.application.training;

import com.drones.vision.domain.model.AuditAction;
import com.drones.vision.domain.model.AuditEntry;
import com.drones.vision.domain.model.AuditTargetType;
import com.drones.vision.domain.model.ModelRef;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.AuditTrailPort;
import com.drones.vision.domain.port.out.ModelRegistryPort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import com.drones.vision.application.scope.AccessDeniedException;
import com.drones.vision.application.scope.VisibilityScope;

/**
 * The one implementation of {@link ModelRegistryService}.
 *
 * <h2>Scope gate</h2>
 * {@link #promote} requires {@link VisibilityScope#canManageOrg()} — any manager/admin, mirroring
 * {@code DefaultDatasetService#create}'s gate exactly (docs/plans/done/CV-TRAINING-PLAN.md §7/§8: promotion is
 * the one privileged control-plane action the registry exposes). {@link #models()} is unscoped and
 * never throws, matching {@code CvModelsController}'s existing "any authenticated caller may read
 * the roster" precedent.
 *
 * <h2>Audit</h2>
 * Every {@link #promote} attempt writes exactly one {@link AuditEntry} against {@link
 * AuditTargetType#MODEL} — a scope denial ({@code DENIED:out of scope}), cv-service's own refusal
 * ({@code REFUSED:<message>}, e.g. an unknown model id — rsync the artifact first — or no registry
 * reachable at all), and a successful promotion ({@code PROMOTED}) alike, reusing {@code
 * DefaultFlightCommandService}'s "audit every attempt, success or refusal" idiom. {@link
 * AuditAction} has no dedicated "promoted" value; {@link AuditAction#UPDATED} is the closest
 * existing fit, the same reasoning {@code DefaultFlightCommandService} already used for
 * "commanded". {@link #models()} never throws and is not audited (a read).
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
    private static final String RESULT_PROMOTED = "PROMOTED";

    private final ModelRegistryPort modelRegistryPort;
    private final AuditTrailPort auditTrail;

    public DefaultModelRegistryService(ModelRegistryPort modelRegistryPort, AuditTrailPort auditTrail) {
        this.modelRegistryPort = Objects.requireNonNull(modelRegistryPort, "modelRegistryPort must not be null");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail must not be null");
    }

    @Override
    public List<RegisteredModel> models() {
        List<ModelRef> all = modelRegistryPort.models();
        Optional<ModelRef> active = modelRegistryPort.active();
        return all.stream()
                .map(ref -> new RegisteredModel(ref, active.isPresent() && active.get().equals(ref)))
                .toList();
    }

    @Override
    public void promote(ModelRef ref, UserId actor, VisibilityScope scope) {
        Objects.requireNonNull(ref, "ref must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");

        if (!scope.canManageOrg()) {
            audit(actor, ref, DENIED_OUT_OF_SCOPE);
            throw new AccessDeniedException("Not permitted to promote models");
        }

        try {
            modelRegistryPort.promote(ref);
        } catch (IllegalStateException e) {
            audit(actor, ref, REFUSED_PREFIX + e.getMessage());
            throw e;
        }
        audit(actor, ref, RESULT_PROMOTED);
    }

    private void audit(UserId actor, ModelRef ref, String result) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(ATTR_MODEL_ID, ref.id());
        attributes.put(ATTR_MODEL_VERSION, ref.version());
        attributes.put(ATTR_RESULT, result);
        String targetId = ref.id() + ":" + ref.version();
        auditTrail.record(AuditEntry.of(actor, AuditAction.UPDATED, AuditTargetType.MODEL, targetId,
                "Promote model " + targetId, attributes));
    }
}

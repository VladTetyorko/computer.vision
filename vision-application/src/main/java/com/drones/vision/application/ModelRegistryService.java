package com.drones.vision.application;

import com.drones.vision.domain.model.ModelRef;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.ModelRegistryPort;

import java.util.List;

/**
 * The CV model registry control plane (docs/CV-TRAINING-PLAN.md §7/§8, Phase 2): list every known
 * model and see which one is live, and promote one to production. One interface, one
 * implementation ({@link DefaultModelRegistryService}) — {@link ModelRegistryPort} itself is the
 * substitutable boundary (gRPC to cv-service today); this service adds nothing but the
 * active-flag computation and the promote gate/audit on top of it.
 *
 * <h2>Scope</h2>
 * {@link #models()} is an unscoped, unaudited read — any authenticated caller may see the roster
 * and which model is active, mirroring {@code CvModelsController}'s existing "any authenticated
 * caller may read the static roster" precedent. {@link #promote} is the one privileged action
 * here, gated on {@link VisibilityScope#canManageOrg()} — any manager/admin, not further
 * restricted to a group (there is no per-model ownership to restrict against, unlike {@link
 * DatasetService#create}'s per-dataset-group precedent) — mirroring {@code
 * DefaultDatasetService#create}'s manage-org gate exactly.
 */
public interface ModelRegistryService {

    /**
     * Lists every known model reference, marking which one (if any) is currently active.
     *
     * @return an immutable snapshot, in the order {@link ModelRegistryPort#models()} reports
     */
    List<RegisteredModel> models();

    /**
     * Promotes a model reference to production.
     *
     * @param ref   the model reference to promote
     * @param actor who is promoting it, for the audit trail
     * @param scope the acting user's visibility; must satisfy {@link VisibilityScope#canManageOrg()}
     * @throws AccessDeniedException if {@code scope} may not manage the organization (audited as a
     *                               denial before this method throws)
     * @throws IllegalStateException if cv-service refuses the promotion — e.g. an unknown model id
     *                                (rsync the artifact first) or no registry reachable at all;
     *                                audited as a refusal, then rethrown unchanged
     */
    void promote(ModelRef ref, UserId actor, VisibilityScope scope);
}

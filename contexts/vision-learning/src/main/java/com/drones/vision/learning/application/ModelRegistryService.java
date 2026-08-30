package com.drones.vision.learning.application;

import com.drones.vision.kernel.UserId;
import com.drones.vision.learning.domain.port.CvModelRepositoryPort;
import com.drones.vision.learning.domain.port.ModelRegistryPort;
import com.drones.vision.platform.VisibilityScope;

/**
 * The CV model registry control plane (docs/plans/done/CV-TRAINING-PLAN.md §7/§8; joined/governed
 * per docs/plans/active/CV-SETTINGS-PLAN.md §3.2, fixing H4 — "the registry throws away what the
 * wire already carries" — and H7's rollback gap): list every known model with platform governance
 * joined onto worker truth, promote one to production, and roll back to whichever model that
 * promotion demoted. One interface, one implementation ({@link DefaultModelRegistryService}) —
 * {@link ModelRegistryPort} and {@link CvModelRepositoryPort} are the two substitutable boundaries
 * this service merges; it adds nothing but that join, the promote/rollback bookkeeping, and the
 * gate/audit on top of both.
 *
 * <h2>Scope</h2>
 * {@link #models()} is an unscoped, unaudited read — any authenticated caller may see the roster,
 * mirroring {@code CvModelsController}'s existing "any authenticated caller may read the roster"
 * precedent, and never throws (docs/plans/active/CV-SETTINGS-PLAN.md §8 OQ5). {@link #promote}/
 * {@link #rollback} are gated on {@link VisibilityScope#canAdminister()} — ADMIN only, not any
 * manager (docs/plans/done/OPS-UX-PLAN.md §1: swapping which model every stream infers with is a
 * deployment-global blast radius no single group's manager should have from managing their own
 * subtree alone).
 */
public interface ModelRegistryService {

    /**
     * Lists every known model, joining platform governance rows with the connected CV worker's own
     * reported roster.
     *
     * @return the joined catalogue, tagged with where it actually came from — see {@link
     *         CatalogSource}. Never throws: an unreachable worker falls back to the deployment's
     *         static config catalogue instead (docs/plans/active/CV-SETTINGS-PLAN.md §8 OQ5).
     */
    CvModelCatalog models();

    /**
     * Promotes a model to production: sets it {@code LIVE} and demotes whichever row was
     * previously {@code LIVE} to {@code RETIRED}, so a later {@link #rollback} can restore it.
     *
     * @param modelId the model id to promote
     * @param version the model version to promote
     * @param actor   who is promoting it, for the audit trail
     * @param scope   the acting user's visibility; must satisfy {@link VisibilityScope#canAdminister()}
     * @return the promotion outcome
     * @throws com.drones.vision.platform.AccessDeniedException if {@code scope} may not administer
     *                                (audited as a denial before this method throws)
     * @throws IllegalArgumentException if {@code modelId}/{@code version} is blank
     * @throws IllegalStateException if cv-service refuses the promotion — e.g. an unknown model id
     *                                (rsync the artifact first) or no worker reachable at all;
     *                                audited as a refusal, then rethrown unchanged
     */
    PromotionResult promote(String modelId, String version, UserId actor, VisibilityScope scope);

    /**
     * Restores whichever model {@link #promote}'s most recent call demoted to {@code RETIRED} back
     * to {@code LIVE}, retiring the current one in its place.
     *
     * @param actor who is rolling back, for the audit trail
     * @param scope the acting user's visibility; must satisfy {@link VisibilityScope#canAdminister()}
     * @return the rollback outcome
     * @throws com.drones.vision.platform.AccessDeniedException if {@code scope} may not administer
     *                                (audited as a denial before this method throws)
     * @throws IllegalStateException if no previously-retired model exists to roll back to, or
     *                                cv-service refuses the promotion; audited as a refusal, then
     *                                thrown
     */
    PromotionResult rollback(UserId actor, VisibilityScope scope);
}

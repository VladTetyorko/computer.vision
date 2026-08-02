package com.drones.vision.application.training;

import com.drones.vision.domain.model.Dataset;
import com.drones.vision.domain.model.DatasetId;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.UserId;

import java.util.List;
import com.drones.vision.application.asset.AssetService;
import com.drones.vision.application.mark.DefaultMarkService;
import com.drones.vision.application.mark.MarkService;
import com.drones.vision.application.scope.AccessDeniedException;
import com.drones.vision.application.scope.VisibilityScope;

/**
 * CRUD over {@link Dataset}s (docs/CV-TRAINING-PLAN.md §2) — one interface, one implementation
 * ({@link DefaultDatasetService}), the labeling/capture side lives in {@link LabelingService}.
 *
 * <h2>Scope (docs/CV-TRAINING-PLAN.md Open Questions §4)</h2>
 * Create/delete are management actions, gated on {@link VisibilityScope#canManageOrg()} — any
 * manager/admin, not only one whose subtree contains the dataset (mirrors {@code
 * DefaultMarkService}'s own "any manager may manage" precedent, not {@code AssetService}'s
 * per-group write gate). {@link #list} and {@link #get} are group-scoped reads: a {@link
 * VisibilityScope.Kind#GROUPS} scope sees only datasets owned by a group in {@link
 * VisibilityScope#includesGroup}; {@link VisibilityScope.Kind#UNBOUNDED} sees everything. Unlike
 * every other scoped read in this codebase, {@link #get} does <b>not</b> hide an out-of-scope
 * dataset behind a 404 — it 403s, per this feature's own frozen contract (a dataset's mere
 * existence is not sensitive the way an asset's is).
 */
public interface DatasetService {

    /**
     * Creates a new {@link Dataset} in {@link com.drones.vision.domain.model.DatasetStatus#OPEN}.
     *
     * @param spec      the dataset's name/target category/class vocabulary
     * @param ownership who owns the new dataset and which group it belongs to (resolved at the API
     *                  edge from the acting user, the same way {@link AssetService#create} and
     *                  {@link MarkService#create} are — never derived from {@code scope} here,
     *                  since a {@link VisibilityScope.Kind#GROUPS} scope carries a manager's whole
     *                  visible subtree, not their own single home group)
     * @param actor     who is creating it, for the audit trail
     * @param scope     the acting user's visibility; must satisfy {@link
     *                  VisibilityScope#canManageOrg()}
     * @return the created, persisted dataset
     * @throws AccessDeniedException if {@code scope} may not manage the organization
     */
    Dataset create(DatasetSpec spec, Ownership ownership, UserId actor, VisibilityScope scope);

    /**
     * Lists every dataset {@code scope} may see.
     *
     * @param actor who is asking (unused beyond the frozen signature today — reads are not
     *              audited; kept for symmetry with the other three methods and any future
     *              per-actor read logging)
     * @param scope the acting user's visibility
     * @return an immutable, scope-filtered snapshot
     */
    List<Dataset> list(UserId actor, VisibilityScope scope);

    /**
     * Reads one dataset.
     *
     * @param id    the dataset to read
     * @param actor who is asking, for the audit trail on a denial
     * @param scope the acting user's visibility
     * @return the dataset
     * @throws java.util.NoSuchElementException if no dataset has that id
     * @throws AccessDeniedException            if {@code id} exists but is outside {@code scope}
     *                                           (audited as a denial, unlike a plain hiding 404)
     */
    Dataset get(DatasetId id, UserId actor, VisibilityScope scope);

    /**
     * Deletes a dataset. Does not cascade to its samples/images (matches {@link
     * com.drones.vision.domain.port.out.DatasetRepositoryPort#delete}'s own "no referential
     * integrity between repositories" contract) — orphaned samples/images for a deleted dataset are
     * a known, accepted gap this wave does not close.
     *
     * @param id    the dataset to delete
     * @param actor who is deleting it, for the audit trail
     * @param scope the acting user's visibility; must satisfy {@link
     *              VisibilityScope#canManageOrg()}
     * @throws java.util.NoSuchElementException if no dataset has that id
     * @throws AccessDeniedException            if {@code scope} may not manage the organization
     */
    void delete(DatasetId id, UserId actor, VisibilityScope scope);
}

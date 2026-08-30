package com.drones.vision.perception.domain.port;

import com.drones.vision.kernel.GroupId;
import com.drones.vision.perception.domain.model.BindingScope;
import com.drones.vision.perception.domain.model.CvProfile;
import com.drones.vision.perception.domain.model.CvProfileBinding;
import com.drones.vision.perception.domain.model.CvProfileId;

import java.util.List;
import java.util.Optional;

/**
 * Driven port: persist and retrieve {@link CvProfile}s and the {@link CvProfileBinding}s that
 * attach them to a scope (docs/plans/active/CV-SETTINGS-PLAN.md §3.1, §5.3 — {@code V29__cv_profiles.sql}).
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #save(CvProfile)} upserts by {@link CvProfile#id()} and returns the persisted
 *       profile.</li>
 *   <li>{@link #findById(CvProfileId)} and {@link #findAll()}/{@link #findAllByGroup(GroupId)}
 *       return snapshots, not live views.</li>
 *   <li>{@link #findAllByGroup(GroupId)} returns only profiles owned by that group — built-in
 *       profiles ({@link CvProfile#groupId()} {@code null}) are never included; a caller that wants
 *       "this group's own profiles plus every built-in" composes {@link #findAll()} itself
 *       (application-layer concern, not this port's).</li>
 *   <li>{@link #delete(CvProfileId)} deletes an unbound profile; deleting an unknown id is a
 *       no-op. Refusing to delete a still-bound profile (the 409 rule, docs/plans/active/CV-SETTINGS-PLAN.md
 *       §5.2) is enforced by the caller via {@link #countBindingsFor(CvProfileId)}, not by this
 *       method.</li>
 *   <li>{@link #saveBinding(CvProfileBinding)} upserts by the composite
 *       {@code (scopeKind, scopeId)} key — a scope may have at most one bound profile at a time,
 *       matching the migration's primary key.</li>
 *   <li>{@link #findBinding(BindingScope, String)} returns {@link Optional#empty()} for an unbound
 *       scope, never {@code null}.</li>
 *   <li>{@link #deleteBinding(BindingScope, String)} is idempotent — deleting an already-unbound
 *       scope is a no-op.</li>
 *   <li>{@link #countBindingsFor(CvProfileId)} counts every binding pointing at a profile,
 *       regardless of scope kind — the exact question the delete-profile 409 rule needs answered.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use — profiles/bindings are read far more often than
 * written (a resolver reads them on every stream {@code start}; W2's {@code CvProfileCache} is
 * expected to be the usual read path), while writes come from infrequent {@code canManageOrg}
 * admin actions.
 */
public interface CvProfileRepositoryPort {

    /**
     * Finds a profile by id.
     *
     * @param id the profile id
     * @return the profile, or {@link Optional#empty()} if none exists
     */
    Optional<CvProfile> findById(CvProfileId id);

    /**
     * Lists every profile — built-in templates and every group's own profiles alike.
     *
     * @return an immutable snapshot of all profiles
     */
    List<CvProfile> findAll();

    /**
     * Lists the profiles owned by one group, excluding built-in templates.
     *
     * @param groupId the owning group
     * @return an immutable snapshot of that group's own profiles; empty if it has none
     */
    List<CvProfile> findAllByGroup(GroupId groupId);

    /**
     * Inserts or updates a profile.
     *
     * @param profile the profile to persist
     * @return the persisted profile
     */
    CvProfile save(CvProfile profile);

    /**
     * Deletes a profile.
     *
     * @param id the profile to delete; deleting an unknown id is a no-op
     */
    void delete(CvProfileId id);

    /**
     * Finds the profile bound to one scope, if any.
     *
     * @param scopeKind which kind of scope {@code scopeId} identifies
     * @param scopeId   the scope's id
     * @return the binding, or {@link Optional#empty()} if that scope has no bound profile
     */
    Optional<CvProfileBinding> findBinding(BindingScope scopeKind, String scopeId);

    /**
     * Lists every binding across every scope.
     *
     * @return an immutable snapshot of all bindings
     */
    List<CvProfileBinding> findAllBindings();

    /**
     * Inserts or replaces the binding for {@code binding}'s {@code (scopeKind, scopeId)}.
     *
     * @param binding the binding to persist
     * @return the persisted binding
     */
    CvProfileBinding saveBinding(CvProfileBinding binding);

    /**
     * Removes the binding for one scope, if any.
     *
     * @param scopeKind which kind of scope {@code scopeId} identifies
     * @param scopeId   the scope's id; removing an already-unbound scope is a no-op
     */
    void deleteBinding(BindingScope scopeKind, String scopeId);

    /**
     * Counts how many scopes (of any kind) are currently bound to {@code profileId} — the
     * still-bound check behind the delete-profile 409 rule (docs/plans/active/CV-SETTINGS-PLAN.md §5.2).
     *
     * @param profileId the profile to check
     * @return the number of bindings pointing at {@code profileId}; {@code 0} if unbound
     */
    int countBindingsFor(CvProfileId profileId);
}

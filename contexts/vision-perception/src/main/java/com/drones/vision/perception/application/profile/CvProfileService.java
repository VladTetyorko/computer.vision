package com.drones.vision.perception.application.profile;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.perception.domain.model.BindingScope;
import com.drones.vision.perception.domain.model.CvProfile;
import com.drones.vision.perception.domain.model.CvProfileBinding;
import com.drones.vision.perception.domain.model.CvProfileId;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.platform.Authority;
import com.drones.vision.platform.VisibilityScope;

import java.util.List;

/**
 * Everything the application does with {@link CvProfile}s and their bindings
 * (docs/plans/active/CV-SETTINGS-PLAN.md &sect;3.1/&sect;5). One interface, one implementation
 * ({@code DefaultCvProfileService}).
 *
 * <h2>Scope gate</h2>
 * {@link #create}, {@link #update}, {@link #delete}, {@link #fork}, {@link #bind} and {@link
 * #unbind} all require {@link Authority#mayManageOrg()} — any manager/admin, not further
 * restricted to a profile's own owning group, matching every other fleet-config gate in this
 * codebase (docs/plans/active/CV-SETTINGS-PLAN.md &sect;8 Q3, and {@code DefaultDatasetService}'s own
 * "create/delete = canManageOrg" precedent). A denied mutation throws {@link
 * com.drones.vision.platform.AccessDeniedException} (403) — the operation was attempted on a thing
 * that does exist, so hiding it would be dishonest, unlike a scoped read. {@link #get}, {@link
 * #effective} and {@link #coverage} instead hide an out-of-scope profile/asset behind {@link
 * java.util.NoSuchElementException} (404), matching {@code AssetService#details(VisibilityScope,
 * AssetId)}'s own convention. {@link #list} never throws — it silently filters to what {@code
 * scope} may see, built-in profiles always included.
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use; all shared state lives behind the injected
 * collaborators.
 */
public interface CvProfileService {

    /**
     * Lists profiles visible to {@code scope}: every built-in profile, plus every profile owned by
     * a group {@code scope} includes.
     *
     * @param actor the acting user
     * @param scope what the acting user may see
     * @return an immutable snapshot, group-filtered
     */
    List<CvProfile> list(UserId actor, VisibilityScope scope);

    /**
     * Reads one profile.
     *
     * @param id    the profile to read
     * @param actor the acting user
     * @param scope what the acting user may see
     * @return the profile
     * @throws java.util.NoSuchElementException if no profile has that id, or it is a non-built-in
     *                                           profile outside {@code scope}
     */
    CvProfile get(CvProfileId id, UserId actor, VisibilityScope scope);

    /**
     * Creates a new, non-built-in profile owned by {@code groupId}.
     *
     * <p>{@code groupId} is a separate, explicit parameter rather than derived from {@code scope} —
     * a {@link VisibilityScope.Kind#GROUPS} scope carries a manager's whole visible subtree, not
     * their own single home group, the same reasoning {@code DatasetService#create} documents for
     * {@code Ownership}.
     *
     * @param spec   what to create
     * @param groupId the owning group
     * @param actor  the acting user
     * @param scope  what the acting user may do
     * @return the created profile
     * @throws com.drones.vision.platform.AccessDeniedException if {@code scope} may not manage the
     *                                                           organization
     */
    CvProfile create(CvProfileSpec spec, GroupId groupId, UserId actor, Authority scope);

    /**
     * Applies a full edit to a non-built-in profile.
     *
     * @param id    the profile to edit
     * @param spec  the new field values
     * @param actor the acting user
     * @param scope what the acting user may do
     * @return the updated profile
     * @throws java.util.NoSuchElementException                 if no profile has that id
     * @throws com.drones.vision.platform.AccessDeniedException if {@code scope} may not manage the
     *                                                           organization
     * @throws IllegalStateException                            if the profile is built-in
     */
    CvProfile update(CvProfileId id, CvProfileSpec spec, UserId actor, Authority scope);

    /**
     * Deletes a non-built-in, unbound profile.
     *
     * @param id    the profile to delete
     * @param actor the acting user
     * @param scope what the acting user may do
     * @throws java.util.NoSuchElementException                 if no profile has that id
     * @throws com.drones.vision.platform.AccessDeniedException if {@code scope} may not manage the
     *                                                           organization
     * @throws IllegalStateException                            if the profile is built-in, or is
     *                                                           still bound to one or more scopes
     */
    void delete(CvProfileId id, UserId actor, Authority scope);

    /**
     * Copies a built-in profile's fields into a new, non-built-in profile owned by {@code groupId}
     * (docs/plans/active/CV-SETTINGS-PLAN.md &sect;3.4: built-in profiles are "not editable, forkable").
     * The built-in source is never modified.
     *
     * @param builtInId the built-in profile to copy
     * @param newName   the new profile's name
     * @param groupId   the owning group for the new profile
     * @param actor     the acting user
     * @param scope     what the acting user may do
     * @return the newly created, non-built-in profile
     * @throws java.util.NoSuchElementException                 if no profile has {@code builtInId}
     * @throws com.drones.vision.platform.AccessDeniedException if {@code scope} may not manage the
     *                                                           organization
     * @throws IllegalArgumentException                         if the source profile is not built-in
     */
    CvProfile fork(CvProfileId builtInId, String newName, GroupId groupId, UserId actor, Authority scope);

    /**
     * Binds a profile to a scope — an organization, a category, or a single asset — replacing
     * whatever was previously bound to that exact scope.
     *
     * @param scopeKind which kind of scope {@code scopeId} identifies
     * @param scopeId   the scope's id: a UUID string for {@link BindingScope#ASSET}/{@link
     *                  BindingScope#ORGANIZATION}, a kebab-case slug for {@link
     *                  BindingScope#CATEGORY}
     * @param profileId the profile to bind
     * @param actor     the acting user
     * @param scope     what the acting user may do
     * @return the persisted binding
     * @throws java.util.NoSuchElementException                 if no profile has {@code profileId}
     * @throws com.drones.vision.platform.AccessDeniedException if {@code scope} may not manage the
     *                                                           organization
     * @throws IllegalArgumentException                         if {@code scopeId} is not a valid id
     *                                                           for {@code scopeKind}
     */
    CvProfileBinding bind(BindingScope scopeKind, String scopeId, CvProfileId profileId, UserId actor,
                          Authority scope);

    /**
     * Removes a scope's binding, if any — idempotent, matching {@link
     * com.drones.vision.perception.domain.port.CvProfileRepositoryPort#deleteBinding}'s own contract.
     *
     * @param scopeKind which kind of scope {@code scopeId} identifies
     * @param scopeId   the scope's id
     * @param actor     the acting user
     * @param scope     what the acting user may do
     * @throws com.drones.vision.platform.AccessDeniedException if {@code scope} may not manage the
     *                                                           organization
     * @throws IllegalArgumentException                         if {@code scopeId} is not a valid id
     *                                                           for {@code scopeKind}
     */
    void unbind(BindingScope scopeKind, String scopeId, UserId actor, Authority scope);

    /**
     * Resolves the one {@link PipelineConfig} {@code assetId} would start with right now — the same
     * asset &rarr; category &rarr; organization &rarr; platform fold {@code DefaultStreamService#start}
     * applies, exposed as a standalone read (docs/plans/active/CV-SETTINGS-PLAN.md &sect;5.2 {@code GET
     * /api/cv/profiles/effective}).
     *
     * @param assetId         the asset to resolve
     * @param platformDefault the configuration to fall back to when no binding matches at any
     *                        level — this module never invents one itself; the caller (the wiring
     *                        layer, mirroring how {@code DefaultStreamService#start}'s own
     *                        {@code requestedConfig} is assembled) supplies it
     * @param actor           the acting user
     * @param scope           what the acting user may see
     * @return the resolved profile (if any), its source, and the folded configuration
     * @throws java.util.NoSuchElementException if no asset has {@code assetId}, or it is outside
     *                                           {@code scope}
     */
    EffectiveProfile effective(AssetId assetId, PipelineConfig platformDefault, UserId actor, VisibilityScope scope);

    /**
     * Resolves every asset {@code scope} may see, for the fleet-wide coverage table
     * (docs/plans/active/CV-SETTINGS-PLAN.md &sect;5.2 {@code GET /api/cv/coverage}).
     *
     * @param platformDefault the configuration to fall back to for an asset with no binding at any
     *                        level — see {@link #effective}'s own javadoc
     * @param actor           the acting user
     * @param scope           what the acting user may see
     * @return one row per asset {@code scope} may see
     */
    List<CoverageRow> coverage(PipelineConfig platformDefault, UserId actor, VisibilityScope scope);
}

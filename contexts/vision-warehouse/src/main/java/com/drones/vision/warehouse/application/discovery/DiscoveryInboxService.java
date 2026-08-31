package com.drones.vision.warehouse.application.discovery;

import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.DiscoveredDevice;
import com.drones.vision.warehouse.domain.model.DiscoveryCandidate;
import com.drones.vision.warehouse.domain.model.DiscoveryCandidateId;

import java.util.List;

/**
 * The discovery inbox — turns discovery from "a scan button feeding a form" into a persisted,
 * deduplicated "found devices" list an operator clicks through (docs/plans/active/
 * ZERO-CONFIG-ONBOARDING-CONTEXT.md &sect;3 P2, &sect;11 Z2a).
 *
 * <p>One interface, one implementation ({@link DefaultDiscoveryInboxService}). Fed by every {@link
 * com.drones.vision.warehouse.application.discovery.DiscoveryService#scan} result (a periodic sweep
 * and/or a manual scan, both outside this module's file scope — {@code vision-app}'s runner and
 * {@code vision-api}'s controller), plus, in a later wave, the standing MAVLink lobby and mediamtx's
 * publish hook. This module only owns what happens once a {@link DiscoveredDevice} exists: dedupe,
 * persistence, and the operator's dismiss/register verbs.
 *
 * <h2>Registering delegates, it does not duplicate</h2>
 * {@link #register} builds an {@code AssetSpec} from the candidate and calls the existing {@link
 * com.drones.vision.warehouse.application.asset.AssetService#createFromCandidate} — this service's
 * first production caller. Every rule that method already enforces (category must exist, duplicate
 * refused naming the owner) applies unchanged; this service adds nothing on top except stamping the
 * candidate itself.
 *
 * <h2>Threading</h2>
 * See {@link DefaultDiscoveryInboxService}'s own javadoc for the concurrency guarantee {@link
 * #report} needs against a periodic sweep runner.
 */
public interface DiscoveryInboxService {

    /**
     * Records one scan result: upserts by {@link DiscoveryCandidate#identityKeyFor(DiscoveredDevice)}.
     *
     * <p>A new identity is recorded {@link com.drones.vision.warehouse.domain.model.CandidateStatus#NEW}
     * unless it already matches an active, registered device (see {@link
     * com.drones.vision.warehouse.application.asset.AssetService#findDuplicateDevice}), in which
     * case it is recorded {@link com.drones.vision.warehouse.domain.model.CandidateStatus#REGISTERED}
     * straight away — never {@code NEW} for something already in the fleet. A previously-seen
     * identity has {@link DiscoveryCandidate#discovered()}/{@link DiscoveryCandidate#lastSeen()}
     * refreshed; its {@link com.drones.vision.warehouse.domain.model.CandidateStatus#DISMISSED}
     * status is carried over unchanged unless the same already-registered check now matches (an
     * operator's dismissal never suppresses an objective "this is already in the fleet" fact).
     *
     * @param discovered the scan result to record
     * @return the upserted candidate
     */
    DiscoveryCandidate report(DiscoveredDevice discovered);

    /**
     * Lists every candidate, in no particular guaranteed order — the inbox's full contents.
     *
     * <p>Unscoped by design, like {@link com.drones.vision.warehouse.application.category.CategoryService}:
     * a not-yet-registered candidate has no {@code Ownership} for a per-instance visibility check to
     * authorise against. {@code vision-api} gates the endpoint on {@code
     * VisibilityScope#canManageOrg()} itself, the same org-level read gate {@code CategoryController}
     * already applies.
     *
     * @return an immutable snapshot
     */
    List<DiscoveryCandidate> candidates();

    /**
     * An operator dismisses a candidate — "not now". A later {@link #report} of the same identity
     * carries the dismissal over unless it now matches an already-registered device.
     *
     * @param id    the candidate to dismiss
     * @param actor the user performing the dismissal
     * @return the dismissed candidate
     * @throws java.util.NoSuchElementException if no candidate has that id
     */
    DiscoveryCandidate dismiss(DiscoveryCandidateId id, UserId actor);

    /**
     * An operator registers a candidate as a new asset: builds an {@code AssetSpec} from the
     * candidate's {@link DiscoveredDevice} and {@code command}'s overrides, and delegates to {@link
     * com.drones.vision.warehouse.application.asset.AssetService#createFromCandidate} — see this
     * interface's own javadoc for why nothing is duplicated here. On success, the candidate itself
     * is stamped {@link com.drones.vision.warehouse.domain.model.CandidateStatus#REGISTERED} with
     * the new asset's id.
     *
     * <p>Authorization mirrors {@code DefaultGroupService#create}'s new-group-under-a-parent gate:
     * {@code !scope.canManageOrg()} refuses outright (registering fleet inventory is team-scoped
     * management, the same gate {@code AssetController#create} applies at the API layer for a
     * direct, non-candidate create); then {@code !scope.includesGroup(command.ownership().groupId())}
     * refuses an attempt to hand the new asset to a group outside the caller's own subtree.
     *
     * @param id      the candidate to register
     * @param command the operator's overrides, including who will own the new asset
     * @param scope   the acting user's visibility, checked as above
     * @param actor   the user performing the registration
     * @return the created asset
     * @throws java.util.NoSuchElementException if no candidate has that id
     * @throws AccessDeniedException            if {@code scope} fails either check above
     * @throws IllegalArgumentException         if the requested category does not exist
     * @throws IllegalStateException            if the candidate's device duplicates an
     *                                           already-registered one (propagated from {@link
     *                                           com.drones.vision.warehouse.application.asset.AssetService#createFromCandidate})
     */
    Asset register(DiscoveryCandidateId id, RegisterFromCandidateCommand command, VisibilityScope scope,
                    UserId actor);
}

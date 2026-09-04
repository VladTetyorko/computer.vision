package com.drones.vision.identity.application;

import com.drones.vision.identity.domain.model.AssignmentRole;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;

import java.util.Optional;
import java.util.Set;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.Authority;
import com.drones.vision.platform.VisibilityScope;

/**
 * Manages the pilot&rarr;asset assignment roster (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2, feature 2;
 * seats added by docs/plans/active/AUTH-ROLES-PLAN.md §3.4/D15, wave B2; {@link #assign}/{@link
 * #unassign} migrated onto {@link Authority}, wave B6) — the write side behind
 * {@code PUT/DELETE /api/assets/{id}/pilots/{userId}} and the read side behind
 * {@code GET /api/me/assignments} (vision-api, a later wave). One interface, one implementation
 * ({@link DefaultAssignmentService}).
 *
 * <p><strong>The &le;-own-scope rule:</strong> a granter may only assign/unassign a pilot to an
 * asset that is within the granter's own {@link VisibilityScope}. A manager cannot hand out an asset
 * they cannot themselves see. Enforced in the application layer; when the granter's scope is
 * {@link VisibilityScope#unbounded()} (ADMIN / auth-off) every asset is in scope, so behavior is
 * unchanged from a world without scoping.
 *
 * <p>{@link #assign}/{@link #unassign} additionally take the granter's own {@code UserId} — a
 * {@link VisibilityScope} carries no identity, and {@link
 * com.drones.vision.platform.AuditAction#GRANTED}/{@link com.drones.vision.platform.AuditAction#REVOKED}
 * entries (D15) require a real actor to attribute the change to.
 */
public interface AssignmentService {

    /**
     * Assigns a pilot to an asset with a seat, idempotently — re-assigning an already-assigned pilot
     * with a different {@link AssignmentRole} changes the seat (docs/plans/active/AUTH-ROLES-PLAN.md
     * §3.4) rather than creating a second link.
     *
     * <p>Records an {@link com.drones.vision.platform.AuditAction#GRANTED} entry
     * ({@link com.drones.vision.platform.AuditTargetType#ASSIGNMENT}, keyed by {@code "<pilot>:<asset>"})
     * whenever the link is newly created or its seat actually changes; re-assigning the identical
     * (pilot, asset, role) is a true no-op and writes nothing.
     *
     * @param pilot        the pilot to assign
     * @param asset        the asset to assign them to
     * @param role         the seat this link grants
     * @param actor        the acting granter's own id, for audit attribution
     * @param granterScope the acting granter's authority
     * @throws java.util.NoSuchElementException if the asset does not exist
     * @throws AccessDeniedException            if the asset is outside {@code granterScope}
     */
    void assign(UserId pilot, AssetId asset, AssignmentRole role, UserId actor, Authority granterScope);

    /**
     * Unassigns a pilot from an asset, idempotently.
     *
     * <p>Records an {@link com.drones.vision.platform.AuditAction#REVOKED} entry
     * ({@link com.drones.vision.platform.AuditTargetType#ASSIGNMENT}) only when a link actually
     * existed to remove; unassigning an already-unassigned pilot writes nothing.
     *
     * @param pilot        the pilot to unassign
     * @param asset        the asset to unassign them from
     * @param actor        the acting granter's own id, for audit attribution
     * @param granterScope the acting granter's authority
     * @throws java.util.NoSuchElementException if the asset does not exist
     * @throws AccessDeniedException            if the asset is outside {@code granterScope}
     */
    void unassign(UserId pilot, AssetId asset, UserId actor, Authority granterScope);

    /**
     * The assets a pilot is currently assigned to.
     *
     * @param pilot the pilot
     * @return an immutable snapshot of assigned asset ids, empty if none
     */
    Set<AssetId> assignmentsFor(UserId pilot);

    /**
     * The seat a pilot holds on an asset — the CREW-CONTROL-PLAN.md IC-2 answer
     * (docs/plans/active/AUTH-ROLES-PLAN.md §3.4, wave B2). Thin pass-through, not scope-checked,
     * same reasoning as {@link #assignmentsFor} — a pilot may read their own seat.
     *
     * @param pilot the pilot
     * @param asset the asset
     * @return the held {@link AssignmentRole}, or {@link Optional#empty()} iff not assigned at all
     */
    Optional<AssignmentRole> roleFor(UserId pilot, AssetId asset);
}

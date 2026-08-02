package com.drones.vision.application.identity;

import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.UserId;

import java.util.Set;
import com.drones.vision.application.scope.AccessDeniedException;
import com.drones.vision.application.scope.VisibilityScope;

/**
 * Manages the pilot&rarr;asset assignment roster (docs/U-SCOPE-PLAN.md, U-e slice 2, feature 2) —
 * the write side behind {@code PUT/DELETE /api/assets/{id}/pilots/{userId}} and the read side behind
 * {@code GET /api/me/assignments} (vision-api, a later wave). One interface, one implementation
 * ({@link DefaultAssignmentService}).
 *
 * <p><strong>The &le;-own-scope rule:</strong> a granter may only assign/unassign a pilot to an
 * asset that is within the granter's own {@link VisibilityScope}. A manager cannot hand out an asset
 * they cannot themselves see. Enforced in the application layer; when the granter's scope is
 * {@link VisibilityScope#unbounded()} (ADMIN / auth-off) every asset is in scope, so behavior is
 * unchanged from a world without scoping.
 */
public interface AssignmentService {

    /**
     * Assigns a pilot to an asset, idempotently.
     *
     * @param pilot        the pilot to assign
     * @param asset        the asset to assign them to
     * @param granterScope the acting granter's visibility scope
     * @throws java.util.NoSuchElementException if the asset does not exist
     * @throws AccessDeniedException            if the asset is outside {@code granterScope}
     */
    void assign(UserId pilot, AssetId asset, VisibilityScope granterScope);

    /**
     * Unassigns a pilot from an asset, idempotently.
     *
     * @param pilot        the pilot to unassign
     * @param asset        the asset to unassign them from
     * @param granterScope the acting granter's visibility scope
     * @throws java.util.NoSuchElementException if the asset does not exist
     * @throws AccessDeniedException            if the asset is outside {@code granterScope}
     */
    void unassign(UserId pilot, AssetId asset, VisibilityScope granterScope);

    /**
     * The assets a pilot is currently assigned to.
     *
     * @param pilot the pilot
     * @return an immutable snapshot of assigned asset ids, empty if none
     */
    Set<AssetId> assignmentsFor(UserId pilot);
}

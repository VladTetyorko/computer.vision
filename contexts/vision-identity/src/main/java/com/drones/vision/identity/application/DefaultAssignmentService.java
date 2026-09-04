package com.drones.vision.identity.application;

import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.identity.domain.model.AssignmentRole;
import com.drones.vision.identity.domain.port.AssignmentRepositoryPort;
import com.drones.vision.warehouse.application.asset.AssetService;

import java.util.Objects;
import java.util.Set;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.VisibilityScope;

/**
 * The one implementation of {@link AssignmentService}.
 *
 * <p>Reaches {@link AssetService#details(AssetId)} for the single fact it needs — does the asset
 * exist, and what group owns it — so the grant check ({@link
 * VisibilityScope#canManage(com.drones.vision.kernel.Ownership)}) can run against the published
 * application service rather than warehouse's {@code AssetRepositoryPort} directly
 * (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R5 — a cross-context read goes through the
 * owning context's service, not its repository port). {@code details(AssetId)} does more work than
 * the old direct repository lookup (it also resolves devices and recent usages), but assign/revoke
 * is roster management, not a hot path, and this is the only published, per-id read
 * {@code AssetService} offers — see this module's MODULE.md Gotchas. Two dependencies, well under
 * the cap.
 *
 * <h2>Authority, not visibility (docs/plans/done/OPS-UX-PLAN.md §1)</h2>
 * A grant/revoke changes who may fly an asset — that is a management action on the asset, not a
 * read of it, so {@link #requireGrantable} gates on {@link
 * VisibilityScope#canManage(com.drones.vision.kernel.Ownership)} rather than {@link
 * VisibilityScope#includes(AssetId, Ownership)}. The two agree for a MANAGER's {@code GROUPS}
 * scope, but not for a PILOT's {@code ASSIGNED_ASSETS} scope: a pilot can see (and fly) the aircraft
 * assigned to them, but seeing it is not authority to re-pilot it, so a pilot may never grant or
 * revoke an assignment, including their own.
 *
 * <h2>Threading</h2>
 * Holds no mutable state — all shared state lives behind the injected ports.
 */
public final class DefaultAssignmentService implements AssignmentService {

    private final AssignmentRepositoryPort assignmentRepository;
    private final AssetService assetService;

    public DefaultAssignmentService(AssignmentRepositoryPort assignmentRepository, AssetService assetService) {
        this.assignmentRepository =
                Objects.requireNonNull(assignmentRepository, "assignmentRepository must not be null");
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
    }

    @Override
    public void assign(UserId pilot, AssetId asset, VisibilityScope granterScope) {
        requireGrantable(pilot, asset, granterScope);
        // AssignmentRole.PILOT is a temporary literal: this wave (AUTH-ROLES-PLAN.md B1) only widens
        // the port to carry a seat; AssignmentService itself gains the AssignmentRole parameter (and
        // stops hardcoding PILOT here) in wave B2, landing next in this same module.
        assignmentRepository.assign(pilot, asset, AssignmentRole.PILOT);
    }

    @Override
    public void unassign(UserId pilot, AssetId asset, VisibilityScope granterScope) {
        requireGrantable(pilot, asset, granterScope);
        assignmentRepository.unassign(pilot, asset);
    }

    @Override
    public Set<AssetId> assignmentsFor(UserId pilot) {
        Objects.requireNonNull(pilot, "pilot must not be null");
        return assignmentRepository.assetsForPilot(pilot);
    }

    /** The asset must exist and the granter must administer it; otherwise this refuses. */
    private void requireGrantable(UserId pilot, AssetId assetId, VisibilityScope granterScope) {
        Objects.requireNonNull(pilot, "pilot must not be null");
        Objects.requireNonNull(assetId, "asset must not be null");
        Objects.requireNonNull(granterScope, "granterScope must not be null");
        Asset asset = assetService.details(assetId).summary().asset(); // NoSuchElementException -> unknown asset
        if (!granterScope.canManage(asset.ownership())) {
            throw new AccessDeniedException(
                    "Asset " + assetId.value() + " is outside your management authority; you may not change its pilots");
        }
    }
}

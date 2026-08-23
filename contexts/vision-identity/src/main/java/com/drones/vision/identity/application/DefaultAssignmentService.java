package com.drones.vision.identity.application;

import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.identity.domain.port.AssignmentRepositoryPort;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.VisibilityScope;

/**
 * The one implementation of {@link AssignmentService}.
 *
 * <p>Reaches the {@link AssetRepositoryPort} directly (rather than through {@link com.drones.vision.warehouse.application.asset.AssetService}) for
 * the single fact it needs — does the asset exist, and what group owns it — so the grant check
 * ({@link VisibilityScope#canManage(com.drones.vision.kernel.Ownership)}) can run without pulling in
 * the full asset-detail assembly. Two dependencies, well under the cap.
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
    private final AssetRepositoryPort assetRepository;

    public DefaultAssignmentService(AssignmentRepositoryPort assignmentRepository,
                                     AssetRepositoryPort assetRepository) {
        this.assignmentRepository =
                Objects.requireNonNull(assignmentRepository, "assignmentRepository must not be null");
        this.assetRepository = Objects.requireNonNull(assetRepository, "assetRepository must not be null");
    }

    @Override
    public void assign(UserId pilot, AssetId asset, VisibilityScope granterScope) {
        requireGrantable(pilot, asset, granterScope);
        assignmentRepository.assign(pilot, asset);
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
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new NoSuchElementException("Unknown asset: " + assetId.value()));
        if (!granterScope.canManage(asset.ownership())) {
            throw new AccessDeniedException(
                    "Asset " + assetId.value() + " is outside your management authority; you may not change its pilots");
        }
    }
}

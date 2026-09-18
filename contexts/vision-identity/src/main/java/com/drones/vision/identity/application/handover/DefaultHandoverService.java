package com.drones.vision.identity.application.handover;

import com.drones.vision.identity.application.AssignmentService;
import com.drones.vision.identity.domain.model.AssignmentRole;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.Authority;
import com.drones.vision.warehouse.application.custody.AssetCustodyService;
import com.drones.vision.warehouse.domain.model.Asset;

import java.util.Objects;

/**
 * The one implementation of {@link HandoverService} — a composition of two lower services, with no
 * port, no state and no gate of its own (see the interface's javadoc for why each of those is
 * deliberate).
 *
 * <h2>Ordering</h2>
 * Custody is written first because it is the half that can legitimately refuse: {@link
 * AssetCustodyService#issue} carries the state guards (unknown asset, not in stock, already held) and
 * the authority check. Assigning first would mean granting a seat on an asset the hand-over then
 * turns out to be unable to issue.
 *
 * <h2>Threading</h2>
 * Holds no mutable state. The compensating return is <em>not</em> a transaction: two independent
 * writes cannot be made atomic across two contexts' repositories from here, so this class buys the
 * next best thing — the caller always sees the failure, never a half-state, and the compensation's
 * own failure is reported alongside rather than instead of the original.
 */
public final class DefaultHandoverService implements HandoverService {

    private final AssetCustodyService assetCustodyService;
    private final AssignmentService assignmentService;

    public DefaultHandoverService(AssetCustodyService assetCustodyService, AssignmentService assignmentService) {
        this.assetCustodyService =
                Objects.requireNonNull(assetCustodyService, "assetCustodyService must not be null");
        this.assignmentService = Objects.requireNonNull(assignmentService, "assignmentService must not be null");
    }

    @Override
    public Asset issue(AssetId assetId, UserId custodianId, String location, UserId actor, Authority authority) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(custodianId, "custodianId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(authority, "authority must not be null");

        Asset issued = assetCustodyService.issue(assetId, custodianId, location, actor, authority);
        try {
            if (assignmentService.roleFor(custodianId, assetId).isEmpty()) {
                assignmentService.assign(custodianId, assetId, AssignmentRole.PILOT, actor, authority);
            }
        } catch (RuntimeException failure) {
            compensate(assetId, actor, authority, failure);
            throw failure;
        }
        return issued;
    }

    @Override
    public Asset returnToStock(AssetId assetId, UserId actor, Authority authority) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(authority, "authority must not be null");
        return assetCustodyService.returnToStock(assetId, actor, authority);
    }

    /**
     * Undoes this call's own custody write so the caller sees a clean failure rather than an asset
     * issued to someone who was never rostered on it. A failing undo is attached to {@code failure}
     * instead of replacing it — the caller asked why the hand-over failed, not why the rollback did.
     */
    private void compensate(AssetId assetId, UserId actor, Authority authority, RuntimeException failure) {
        try {
            assetCustodyService.returnToStock(assetId, actor, authority);
        } catch (RuntimeException undoFailed) {
            failure.addSuppressed(undoFailed);
        }
    }
}

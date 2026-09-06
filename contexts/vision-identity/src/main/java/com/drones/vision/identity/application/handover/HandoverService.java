package com.drones.vision.identity.application.handover;

import com.drones.vision.identity.application.AssignmentService;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.Authority;
import com.drones.vision.warehouse.application.custody.AssetCustodyService;
import com.drones.vision.warehouse.domain.model.Asset;

import java.util.NoSuchElementException;

/**
 * Hands an asset over to a person: physical custody <em>and</em> the authorisation to fly it, in one
 * command (docs/plans/active/INVENTORY-REWORK-PLAN.md D1).
 *
 * <p><strong>Why this lives in identity, above two lower services.</strong> Issuing used to be
 * {@link AssetCustodyService#issue} alone, with the {@code /add-source} wizard's Hand-over step
 * calling {@code PUT /api/assets/{id}/pilots/{userId}} as a second, client-side request
 * (docs/plans/active/INVENTORY-REWORK-CONTEXT.md §3, defect B). A pilot issued an asset from
 * {@code /assets} — where no such second call was ever made — therefore held the aircraft in their
 * hand while their {@code ASSIGNED_ASSETS} scope still excluded it, so they could not see it at all.
 * Custody is warehouse's fact and assignment is identity's; identity already depends on warehouse
 * (never the reverse), so identity is the only layer that may compose the two. Nothing new is
 * modelled here — this service owns the <em>order</em> and the <em>failure semantics</em> of two
 * calls it does not otherwise change.
 *
 * <h2>Authorisation is unchanged</h2>
 * Neither method gates anything itself. {@link AssetCustodyService} and {@link AssignmentService}
 * each already require {@link Authority#mayManageFleet(com.drones.vision.kernel.Ownership)} over the
 * asset's own ownership and refuse with {@link AccessDeniedException} otherwise, and both resolve the
 * same asset, so a caller that may issue may also assign. Adding a third gate here would only give a
 * future reader two places to look for one rule.
 *
 * <h2>Audit is unchanged</h2>
 * The two lower services write their own entries ({@code UPDATED}/{@code ASSET} for custody,
 * {@code GRANTED}/{@code ASSIGNMENT} for the seat). This service writes none of its own: a
 * hand-over is exactly those two recorded facts, and a third summary row would say nothing the pair
 * does not already say while doubling what an auditor must reconcile.
 *
 * <p>One interface, one implementation ({@link DefaultHandoverService}).
 */
public interface HandoverService {

    /**
     * Issues an in-stock asset to a custodian and, unless they already hold a seat on it, assigns
     * them as its {@link com.drones.vision.identity.domain.model.AssignmentRole#PILOT}.
     *
     * <p><strong>Idempotent assignment.</strong> When the (custodian, asset) link already exists it
     * is left exactly as it is — including a {@code CREW} seat, which is deliberately <em>not</em>
     * promoted to {@code PILOT}: someone already rostered on this asset had their seat chosen by a
     * manager, and handing them the box is not a decision to change it.
     *
     * <p><strong>Compensation.</strong> Custody is written first (it carries the state guards). If
     * the assignment then fails for any reason, custody is returned to stock before the original
     * failure is rethrown, so a caller never observes a half-completed hand-over. A compensation that
     * itself fails is attached to the original exception as a suppressed exception — the caller still
     * sees why the hand-over failed, never why the undo did.
     *
     * @param assetId     the asset to hand over
     * @param custodianId the person receiving it
     * @param location    a free-form note of where it is going, or {@code null}
     * @param actor       the acting user, for audit attribution
     * @param authority   the acting user's authority
     * @return the asset as custody left it
     * @throws NoSuchElementException if the asset is unknown (404)
     * @throws AccessDeniedException  if {@code authority} may not manage this asset (403)
     * @throws IllegalStateException  if the asset is not stored {@code IN_STOCK}, or already has a
     *                                custodian (409) — see {@link AssetCustodyService#issue}
     */
    Asset issue(AssetId assetId, UserId custodianId, String location, UserId actor, Authority authority);

    /**
     * Returns an issued asset to stock, clearing its custody. <strong>The assignment stays</strong>
     * (docs/plans/active/INVENTORY-REWORK-PLAN.md D2): authorisation to fly an aircraft outlives
     * physical possession of it, and a shift that ends with the drone back on the shelf is not a
     * decision to take the pilot off the roster. Revoking a seat stays an explicit act through
     * {@link AssignmentService#unassign}.
     *
     * @param assetId   the asset to return
     * @param actor     the acting user, for audit attribution
     * @param authority the acting user's authority
     * @return the asset as custody left it
     * @throws NoSuchElementException if the asset is unknown (404)
     * @throws AccessDeniedException  if {@code authority} may not manage this asset (403)
     * @throws IllegalStateException  if the asset is not effectively issued (409)
     */
    Asset returnToStock(AssetId assetId, UserId actor, Authority authority);
}

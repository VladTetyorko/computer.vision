package com.drones.vision.warehouse.application.custody;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.MaintenanceKind;
import java.util.NoSuchElementException;

/**
 * Moves an asset through the warehouse-to-field lifecycle (docs/plans/active/WAREHOUSE-UX-PLAN.md
 * &sect;3.4): {@code IN_STOCK} &rarr; {@code ISSUED} &rarr; {@code IN_STOCK} &rarr; {@code
 * MAINTENANCE} &rarr; {@code IN_STOCK} &rarr; {@code RETIRED}. {@code IN_FIELD} is not a verb here
 * — it is derived the moment a usage opens (see {@code InventoryStates#effective}), never a state
 * this service sets.
 *
 * <p>Every verb authorises the same way {@code AssetService}'s command verbs do (mirroring
 * vision-flight's {@code DefaultVehicleProfileService#probe}): the caller's {@link
 * VisibilityScope#canManage(com.drones.vision.kernel.Ownership)} must hold over the asset's
 * ownership, else a denial is audited and {@link AccessDeniedException} is thrown — an honest 403,
 * not a hiding 404, since the caller already knows the asset exists. Every successful verb writes
 * an audit entry and stamps {@link Asset#updatedAt()}.
 *
 * <h2>State guards</h2>
 * <ul>
 *   <li>{@link #issue} requires the asset to be stored {@code IN_STOCK} with no custodian already
 *       set — an already-issued asset must be {@link #returnToStock}'d before it can be issued
 *       again, since both states share the same stored {@code IN_STOCK} value.</li>
 *   <li>{@link #returnToStock} requires it to be effectively {@code ISSUED} (stored {@code
 *       IN_STOCK} with a custodian set) — an asset in {@code MAINTENANCE} must go through {@link
 *       #release} instead, even if custody was kept while grounded.</li>
 *   <li>{@link #ground} refuses only a {@link com.drones.vision.warehouse.domain.model.InventoryState#RETIRED}
 *       asset; it works from stock or issued, keeping custody as-is.</li>
 *   <li>{@link #release} requires the asset to be stored {@code MAINTENANCE}; it clears custody —
 *       a repaired asset always returns through the stockroom, never silently back into the same
 *       custodian's hands.</li>
 *   <li>{@link #retire} refuses an asset that is still issued (return it first) and is idempotent
 *       on an already-retired asset.</li>
 * </ul>
 */
public interface AssetCustodyService {

    /**
     * Hands an in-stock asset to a custodian.
     *
     * @param id          the asset to issue
     * @param custodianId the user receiving custody
     * @param location    a free-form note of where it is going, or {@code null}
     * @param actor       the acting user
     * @param scope       the acting user's visibility scope
     * @return the updated asset
     * @throws NoSuchElementException   if the asset is unknown (404)
     * @throws AccessDeniedException    if {@code scope} may not manage this asset (403)
     * @throws IllegalStateException    if the asset is not stored {@code IN_STOCK}, or already has
     *                                  a custodian (409) — re-issuing an already-issued asset must
     *                                  go through {@link #returnToStock} first, even though both
     *                                  states share the same stored {@code IN_STOCK} value
     */
    Asset issue(AssetId id, UserId custodianId, String location, UserId actor, VisibilityScope scope);

    /**
     * Returns an issued asset to stock, clearing its custody.
     *
     * @param id    the asset to return
     * @param actor the acting user
     * @param scope the acting user's visibility scope
     * @return the updated asset
     * @throws NoSuchElementException if the asset is unknown (404)
     * @throws AccessDeniedException  if {@code scope} may not manage this asset (403)
     * @throws IllegalStateException  if the asset is not effectively issued (409)
     */
    Asset returnToStock(AssetId id, UserId actor, VisibilityScope scope);

    /**
     * Grounds an asset: opens a {@link com.drones.vision.warehouse.domain.model.MaintenanceRecord}
     * of the given kind and sets the stored inventory state to {@code MAINTENANCE}. Custody, if
     * any, is kept — grounding a pilot's aircraft does not take it away from them on paper, but
     * {@link #release} still returns it through the stockroom.
     *
     * @param id      the asset to ground
     * @param kind    what kind of maintenance record to open
     * @param summary a human-readable description; must not be blank
     * @param actor   the acting user
     * @param scope   the acting user's visibility scope
     * @return the updated asset
     * @throws NoSuchElementException if the asset is unknown (404)
     * @throws AccessDeniedException  if {@code scope} may not manage this asset (403)
     * @throws IllegalStateException  if the asset is retired (409)
     */
    Asset ground(AssetId id, MaintenanceKind kind, String summary, UserId actor, VisibilityScope scope);

    /**
     * Releases an asset from maintenance: closes every open, flight-blocking maintenance record
     * ({@link MaintenanceKind#blocksFlight()}) and returns the stored inventory state to {@code
     * IN_STOCK}, clearing custody. An open, non-blocking record ({@code REPAIR}/{@code NOTE}) is
     * left open.
     *
     * @param id    the asset to release
     * @param actor the acting user
     * @param scope the acting user's visibility scope
     * @return the updated asset
     * @throws NoSuchElementException if the asset is unknown (404)
     * @throws AccessDeniedException  if {@code scope} may not manage this asset (403)
     * @throws IllegalStateException  if the asset is not stored {@code MAINTENANCE} (409)
     */
    Asset release(AssetId id, UserId actor, VisibilityScope scope);

    /**
     * Retires an asset for good. Does not delete it — history stays intact. Idempotent: retiring
     * an already-retired asset is a no-op.
     *
     * @param id    the asset to retire
     * @param actor the acting user
     * @param scope the acting user's visibility scope
     * @return the updated asset
     * @throws NoSuchElementException if the asset is unknown (404)
     * @throws AccessDeniedException  if {@code scope} may not manage this asset (403)
     * @throws IllegalStateException  if the asset is still issued (409)
     */
    Asset retire(AssetId id, UserId actor, VisibilityScope scope);
}

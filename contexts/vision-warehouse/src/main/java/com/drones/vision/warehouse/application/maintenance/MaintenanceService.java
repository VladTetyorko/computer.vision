package com.drones.vision.warehouse.application.maintenance;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.domain.model.MaintenanceId;
import com.drones.vision.warehouse.domain.model.MaintenanceKind;
import com.drones.vision.warehouse.domain.model.MaintenanceRecord;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * User-facing maintenance-record management: open a record directly (without also grounding the
 * asset through {@code AssetCustodyService#ground}), close one, and list an asset's history — the
 * surface a maintenance page (docs/plans/active/WAREHOUSE-UX-PLAN.md wave W7) drives.
 *
 * <p>{@link #open}/{@link #close} authorise exactly like {@code AssetCustodyService}'s verbs
 * ({@link VisibilityScope#canManage}); {@link #listForAsset} is a read and authorises on {@link
 * VisibilityScope#includes} instead — visibility, not authority, matching {@code
 * AssetService#details(VisibilityScope, AssetId)}'s own split.
 */
public interface MaintenanceService {

    /**
     * Opens a maintenance record against an asset, without changing its inventory state — use
     * {@code AssetCustodyService#ground} instead when the record should also ground the asset.
     *
     * @param assetId the asset the record is against
     * @param kind    what kind of record to open
     * @param summary a human-readable description; must not be blank
     * @param actor   the acting user
     * @param scope   the acting user's visibility scope
     * @return the opened record
     * @throws NoSuchElementException if the asset is unknown (404)
     * @throws AccessDeniedException  if {@code scope} may not manage this asset (403)
     */
    MaintenanceRecord open(AssetId assetId, MaintenanceKind kind, String summary, UserId actor, VisibilityScope scope);

    /**
     * Closes an open maintenance record.
     *
     * @param id    the record to close
     * @param actor the acting user
     * @param scope the acting user's visibility scope
     * @return the closed record
     * @throws NoSuchElementException if the record or its asset is unknown (404)
     * @throws AccessDeniedException  if {@code scope} may not manage the record's asset (403)
     */
    MaintenanceRecord close(MaintenanceId id, UserId actor, VisibilityScope scope);

    /**
     * Lists every maintenance record ever opened against an asset, open or closed.
     *
     * @param assetId the asset id
     * @param scope   the acting user's visibility scope
     * @return an immutable snapshot of the asset's maintenance history
     * @throws NoSuchElementException if the asset is unknown or out of scope (404)
     */
    List<MaintenanceRecord> listForAsset(AssetId assetId, VisibilityScope scope);
}

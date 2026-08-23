package com.drones.vision.map.application.track;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.map.domain.model.CameraPose;

import java.util.List;
import java.util.Optional;

/**
 * CRUD over one asset's {@link CameraPose} (docs/plans/done/FIXED-CAMERA-GEO-PLAN.md decision D4),
 * audited through {@code AuditTrailPort} — this context's first audit write (D10).
 *
 * <p>One interface, one implementation ({@link DefaultCameraPoseService}).
 *
 * <h2>Authorization</h2>
 * Unlike {@link com.drones.vision.map.application.mark.MarkService}/{@link
 * com.drones.vision.map.application.MapLayerService} (which gate on {@link
 * com.drones.vision.map.application.MapAccessPolicy.Viewer}, this module's own layer-visibility
 * model), a camera pose is asset-scoped, not layer-scoped. Per D10 it is gated at the {@code
 * vision-api} edge exactly like every other asset command — {@code
 * com.drones.vision.platform.VisibilityScope#canManage} decides the 403 before this service is ever
 * called, the same split {@code AssetController} already uses for asset writes. This service takes a
 * plain {@link UserId} actor, not a scope, and never throws for authorization — only for a malformed
 * input.
 *
 * <h2>Threading</h2>
 * Holds no mutable state of its own — all shared state is reached through the injected {@code
 * CameraPoseRepositoryPort}/{@code AuditTrailPort}.
 */
public interface CameraPoseService {

    /**
     * Lists every stored camera pose — what {@code TrackProjectionRunner} (vision-app) iterates each
     * tick to find which assets are calibrated.
     *
     * @return an immutable snapshot of every stored pose
     */
    List<CameraPose> list();

    /**
     * Finds an asset's stored pose.
     *
     * @param assetId the asset id
     * @return the pose, or {@link Optional#empty()} if none is stored (→404 at the API edge)
     */
    Optional<CameraPose> find(AssetId assetId);

    /**
     * Sets (creates or replaces) an asset's camera pose — manual entry, or confirming a calibration
     * solve. Always audits: {@code CREATED} if the asset had no pose before, {@code UPDATED}
     * otherwise.
     *
     * @param assetId the asset id
     * @param input   the pose to set
     * @param actor   who is setting it
     * @return the persisted pose
     * @throws IllegalArgumentException if {@code input}'s fields fail {@link CameraPose}'s own range
     *                                   validation
     */
    CameraPose put(AssetId assetId, CameraPoseInput input, UserId actor);

    /**
     * Removes an asset's stored pose, if any. Idempotent — deleting an asset with no stored pose
     * succeeds silently and audits nothing (there is nothing to say "was deleted").
     *
     * @param assetId the asset id
     * @param actor   who is deleting it
     */
    void delete(AssetId assetId, UserId actor);
}

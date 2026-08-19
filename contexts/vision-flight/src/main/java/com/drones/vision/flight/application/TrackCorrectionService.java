package com.drones.vision.flight.application;

import com.drones.vision.flight.domain.model.TrackCorrection;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.kernel.VisualFix;
import com.drones.vision.platform.VisibilityScope;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Gates a {@link VisualFix} into the aircraft's own corrected track (docs/plans/active/
 * VISUAL-GEO-V2-PLAN.md §3.5). One implementation: {@link DefaultTrackCorrectionService}.
 */
public interface TrackCorrectionService {

    /**
     * Applies the §4.3 Java-side gates and the §4.5 divergence rule to one measured fix, persists
     * and publishes the result. Not a user command — called by the composition-layer runner that
     * drives one localization session, so it takes no {@link VisibilityScope}/actor (the same
     * posture {@code GeofenceMonitor#evaluate} takes).
     *
     * @param assetId        the aircraft this fix is about
     * @param usageId        the flight session this fix belongs to
     * @param fix            the measured (or refused) visual fix
     * @param rawAtFrameTime the aircraft's own reported telemetry at {@code fix.frameAt()}; {@code
     *                       null} when no sample is close enough in time (a telemetry gap) — see
     *                       {@code DefaultTrackCorrectionService}'s own javadoc
     * @return the persisted, published correction
     */
    TrackCorrection submit(AssetId assetId, UsageId usageId, VisualFix fix, Telemetry rawAtFrameTime);

    /**
     * A scoped read: the corrections recorded for one usage, oldest-&gt;newest (the replay use case).
     *
     * @param usageId the usage id
     * @param limit   maximum number of corrections to return; must be positive
     * @param scope   the caller's visibility scope
     * @return an immutable snapshot; empty when the usage has no corrections or belongs to an asset
     *         outside {@code scope}
     */
    List<TrackCorrection> forUsage(UsageId usageId, int limit, VisibilityScope scope);

    /**
     * A scoped read: the freshest correction for one asset (the live cockpit use case).
     *
     * @param assetId the asset id
     * @param scope   the caller's visibility scope
     * @return the freshest correction, absent when there is none or {@code assetId} is outside
     *         {@code scope}
     */
    Optional<TrackCorrection> latest(AssetId assetId, VisibilityScope scope);

    /**
     * Deletes every correction older than {@code before}. Called by the composition-layer runner on
     * its own cadence, not a user-facing operation.
     *
     * @param before the retention horizon
     * @return how many rows were deleted
     */
    int prune(Instant before);
}

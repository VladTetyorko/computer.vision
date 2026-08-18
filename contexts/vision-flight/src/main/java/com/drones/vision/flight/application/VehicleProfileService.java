package com.drones.vision.flight.application;

import com.drones.vision.flight.domain.model.VehicleProfile;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.VisibilityScope;

import java.time.Duration;

/**
 * The PROBE stage (docs/plans/active/DRONE-ONBOARDING-PLAN.md section 3.1) -- observe a vehicle and
 * remember what was observed. See {@link DefaultVehicleProfileService} for the one implementation
 * and its scope/audit gate.
 */
public interface VehicleProfileService {

    /**
     * Actively probes the device behind {@code assetId} and persists the resulting snapshot.
     *
     * @throws java.util.NoSuchElementException                if {@code assetId} is unknown (404)
     * @throws com.drones.vision.platform.AccessDeniedException if the actor may not manage this
     *                                                            asset (403, audited) -- probing puts
     *                                                            traffic on the aircraft's link
     * @throws IllegalStateException                             if the asset has no device this
     *                                                            platform can probe (409, not an
     *                                                            attempt, not audited)
     */
    VehicleProfile probe(AssetId assetId, Duration window, UserId actor, VisibilityScope scope);

    /**
     * The most recently observed profile for {@code assetId}, scoped like every other asset read.
     *
     * @throws java.util.NoSuchElementException if {@code assetId} is unknown, out of scope, or has
     *                                          never been probed (404 in all three cases -- a scoped
     *                                          read never distinguishes "not yours" from "does not
     *                                          exist" from "nothing to show yet")
     */
    VehicleProfile latestProfile(AssetId assetId, VisibilityScope scope);

    /**
     * The pre-registration probe (docs/plans/active/DRONE-ONBOARDING-PLAN.md section 3.1, stage 3):
     * observes a candidate keyed only by {@code linkKey}, before any {@code Asset}/{@code Device}
     * exists to scope against or persist a snapshot against (D7 -- registration is the last stage).
     *
     * @param linkKey the candidate's identity ({@code "udp://host:port#sysid"})
     * @param window  the probe budget
     * @param actor   who requested the probe -- carried for parity with every other entry point in
     *               this service even though, absent an asset, there is nothing yet to audit against
     * @return the observed snapshot, never persisted by this call
     */
    VehicleProfile probeCandidate(String linkKey, Duration window, UserId actor);
}

package com.drones.vision.flight.application.link;

import com.drones.vision.flight.domain.model.LinkGroupView;
import com.drones.vision.flight.domain.model.LinkId;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;

/**
 * Application-facing use case over {@code VehicleLinkPort} (LINK-PAIRING-PLAN.md §3.4 frozen
 * contract): read an asset's current link-election state, and let an operator pin/release it.
 */
public interface LinkStateService {

    /** {@code assetId}'s current link-election snapshot. */
    LinkGroupView linksFor(AssetId assetId);

    /**
     * Operator override: pins {@code assetId}'s ACTIVE link to {@code linkId}.
     *
     * @param actorId who performed the pin, for audit purposes
     * @return the resulting snapshot
     */
    LinkGroupView pin(AssetId assetId, LinkId linkId, UserId actorId);

    /**
     * Releases an operator pin on {@code assetId}'s link group, if any, handing control back to
     * automatic election.
     *
     * @param actorId who performed the release, for audit purposes
     * @return the resulting snapshot
     */
    LinkGroupView release(AssetId assetId, UserId actorId);
}

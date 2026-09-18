package com.drones.vision.flight.domain.model;

import com.drones.vision.kernel.AssetId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A point-in-time view of one asset's paired MAVLink links and which one (if any) is ACTIVE
 * (LINK-PAIRING-PLAN.md §3.4 frozen contract).
 *
 * @param assetId        the asset these links belong to
 * @param links          every link this asset's paired device(s) have ever been heard on, in no
 *                        particular order — see {@link LinkView#deviceId()} for the multi-device case
 * @param activeLinkId   the current ACTIVE link, or {@code null} if none (hard timeout with no
 *                        healthy alternative, or no link has ever been heard from at all)
 * @param pinned         {@code true} iff an operator pin is currently in effect
 * @param lastFailoverAt when the ACTIVE link last changed via an <b>automatic</b> election — never
 *                        moved by an operator pin/release; {@code null} if it has never changed
 */
public record LinkGroupView(AssetId assetId, List<LinkView> links, LinkId activeLinkId, boolean pinned,
                             Instant lastFailoverAt) {

    public LinkGroupView {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(links, "links must not be null");
        links = List.copyOf(links);
        // activeLinkId/lastFailoverAt are deliberately nullable -- see class javadoc.
    }
}

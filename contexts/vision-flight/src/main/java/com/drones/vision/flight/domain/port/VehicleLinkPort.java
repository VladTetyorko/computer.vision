package com.drones.vision.flight.domain.port;

import com.drones.vision.flight.domain.model.LinkGroupView;
import com.drones.vision.flight.domain.model.LinkId;
import com.drones.vision.kernel.AssetId;

/**
 * Driven port: an asset's current paired-link election state, and operator control over it
 * (LINK-PAIRING-PLAN.md §3.4 frozen contract). Implemented by {@code drone-link/mavlink}'s {@code
 * MavlinkVehicleLinkPort}, which resolves {@code AssetId} &rarr; {@code DeviceId} (via {@code
 * AssetService}/{@code Asset.devices()}, filtered to the MAVLink-protocol device(s) — not a new
 * cross-context port, see that class's own javadoc) before reaching {@code MavlinkGateway}'s
 * election state for the resolved sysid(s).
 *
 * <h2>Contract</h2>
 * {@link #linksFor} must never throw for an asset with no paired MAVLink device or one that has
 * never been heard from — it returns a {@link LinkGroupView} with an empty {@code links} list and a
 * {@code null} {@code activeLinkId} instead, matching CLAUDE.md rule 7's "degrade honestly" — the
 * absence of a link is a real, representable state, not an error.
 */
public interface VehicleLinkPort {

    /** {@code assetId}'s current link-election snapshot. Never {@code null} — see the interface javadoc. */
    LinkGroupView linksFor(AssetId assetId);

    /**
     * Operator override: pins {@code assetId}'s ACTIVE link to {@code linkId}.
     *
     * @throws IllegalArgumentException if {@code assetId} has no paired MAVLink device, or {@code
     *                                   linkId} is not one of its known links
     */
    void pin(AssetId assetId, LinkId linkId);

    /** Releases an operator pin on {@code assetId}'s link group, if any, handing control back to automatic election. */
    void release(AssetId assetId);
}

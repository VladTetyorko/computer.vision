package com.drones.vision.flight.domain.port;

import com.drones.vision.flight.domain.model.LinkGroupView;
import com.drones.vision.flight.domain.model.LinkId;
import com.drones.vision.kernel.AssetId;

import java.util.function.Consumer;

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

    /**
     * Subscribes {@code listener} to be told, by {@link AssetId}, whenever that asset's
     * link-election group changes on its own — a link appearing or disappearing, or the ACTIVE link
     * changing (docs/plans/active/LINK-PAIRING-PLAN.md §8 defect #5: without this, a client on the
     * live-update transport, which never polls, never learns of a failover). Not fired for {@link
     * #pin}/{@link #release} themselves — a caller driving those already re-reads the fresh state
     * right after, so a second notification for the same change would be redundant.
     *
     * <h2>Threading</h2>
     * Fires from whatever thread the underlying transport delivers frames on, never the calling
     * thread that registered the listener. A listener must return quickly (no blocking I/O) and
     * must not throw past this call — an implementation must guarantee a listener's own exception
     * never propagates back into the frame-delivery thread that triggered it.
     *
     * @param listener called with the changed asset's id, any number of times over this port's
     *                  lifetime — this is a subscribe, not a one-shot registration
     */
    void onGroupChanged(Consumer<AssetId> listener);
}

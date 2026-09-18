package com.drones.vision.flight.domain.port;

import com.drones.vision.flight.domain.model.LinkGroupView;
import com.drones.vision.kernel.AssetId;

/**
 * Driven port: announce an asset's current {@link LinkGroupView} live, so a driving adapter can
 * push it to connected viewers on the {@code links:<assetId>} live topic (LINK-PAIRING-PLAN.md §3.4,
 * wave L3) — copies {@link GeofenceLiveUpdatePort}'s shape exactly.
 *
 * <h2>Contract</h2>
 * Must return quickly and must not throw for an ordinary delivery failure — a disconnected viewer,
 * a full connection registry, or the feature being disabled entirely (a no-op implementation) must
 * never surface as an exception on the caller's own hot path. The payload is always the whole
 * snapshot (LINK-PAIRING-PLAN.md §3.4's "one topic, one full-state payload, never a delta"), never a
 * delta.
 *
 * <h2>Threading</h2>
 * Called from {@code DefaultLinkStateService} after every {@code VehicleLinkPort} read/pin/release —
 * a rare, operator-driven or election-driven write, not a hot path, but implementations must still
 * be cheap and effectively fire-and-forget. Safe for concurrent use.
 */
public interface LinkStateLiveUpdatePort {

    /**
     * Announces {@code assetId}'s current link-election snapshot.
     *
     * @param assetId  the asset this snapshot belongs to
     * @param snapshot the full current state
     */
    void publishLinks(AssetId assetId, LinkGroupView snapshot);
}

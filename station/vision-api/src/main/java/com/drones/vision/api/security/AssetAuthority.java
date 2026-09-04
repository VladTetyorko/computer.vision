package com.drones.vision.api.security;

import com.drones.vision.kernel.AssetId;

/**
 * Whether the current caller may command <em>one specific asset</em> — the per-asset command axis,
 * sibling to {@link com.drones.vision.platform.Authority}'s deployment/org-wide questions
 * (docs/plans/active/AUTH-ROLES-PLAN.md §3.9).
 *
 * <p>This interface's shape and package are a frozen join point with
 * <a href="../../../../../../../../docs/plans/active/CREW-CONTROL-PLAN.md">CREW-CONTROL-PLAN.md</a>
 * §3.7: whichever plan reaches this path first creates it at this exact signature, and the other
 * finds it already here — a bean swap, never a refactor. AUTH-ROLES-PLAN wave B4 is the one that
 * shipped it, supplying {@link CapabilityAssetAuthority} as the one real implementation.
 *
 * <p>A request-scoped question, not a request-scoped bean: the implementation reads {@link
 * CurrentUser} fresh on every call, exactly as {@link CurrentUser} itself re-reads its {@link
 * PrincipalResolver} on every call — no Spring {@code @RequestScope} proxy is needed for either.
 */
public interface AssetAuthority {

    /**
     * Whether the caller may command this asset's flight — arm/disarm/mode/RTL/estop/aux, and the
     * {@code /ws/manual-control} engage.
     *
     * @param asset the asset to command
     * @return {@code true} iff the caller may fly it
     */
    boolean mayFly(AssetId asset);

    /**
     * Whether the caller may operate this asset's payload — stream start/stop, {@code /api/cv/**},
     * tracker control, per-stream profile apply.
     *
     * @param asset the asset whose payload to operate
     * @return {@code true} iff the caller may operate it
     */
    boolean mayOperateCamera(AssetId asset);

    /**
     * Whether the caller may force a seat change on this asset — take over control from whoever
     * currently holds it, regardless of the current holder's own consent.
     *
     * <p>Deliberately not consumed by anything in this wave: {@code mayForceSeat} is CREW-CONTROL's
     * verb on CREW-CONTROL's surface (docs/plans/active/AUTH-ROLES-PLAN.md §3.9); this plan only
     * supplies its answer.
     *
     * @param asset the asset to force a seat change on
     * @return {@code true} iff the caller may force it
     */
    boolean mayForceSeat(AssetId asset);
}

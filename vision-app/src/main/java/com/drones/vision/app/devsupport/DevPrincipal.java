package com.drones.vision.app.devsupport;

import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;

import java.util.UUID;

/**
 * Constant dev principal that owns every asset created while the platform
 * has no real identity/accounts phase (ARCHITECTURE.md §6) — a fixed,
 * well-known {@link UserId}/{@link GroupId} pair, applied only here in the
 * app-layer wiring ({@link com.drones.vision.app.config.WiringConfiguration}
 * constructs {@code AssetService} with {@link #OWNERSHIP}) and never
 * hard-coded in the domain or application layers, per
 * docs/plans/done/ASSET-MODEL-PLAN.md §0.3/§4.
 *
 * <p>Replaced by real per-request principals resolved from an identity
 * provider, planned for Phase 6.
 */
public final class DevPrincipal {

    /** Fixed dev-mode owning user: wraps {@code UUID(0, 0)}. */
    public static final UserId USER_ID = new UserId(new UUID(0, 0));

    /** Fixed dev-mode owning group: wraps {@code UUID(0, 1)}. */
    public static final GroupId GROUP_ID = new GroupId(new UUID(0, 1));

    /** Ownership applied to every asset created while this dev principal is in effect. */
    public static final Ownership OWNERSHIP = new Ownership(USER_ID, GROUP_ID);

    private DevPrincipal() {
    }
}

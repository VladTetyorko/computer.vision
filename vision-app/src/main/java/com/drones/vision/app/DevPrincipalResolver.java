package com.drones.vision.app;

import com.drones.vision.api.PrincipalResolver;
import com.drones.vision.app.devsupport.DevPrincipal;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.UserId;

/**
 * The {@link PrincipalResolver} wired when {@code vision.auth.enabled=false} (the default) — every
 * request is the fixed {@link DevPrincipal}, exactly the pre-auth behavior (docs/U-AUTH-PLAN.md,
 * wave 3). Identical in effect to {@code PrincipalResolver.fixed(DevPrincipal.OWNERSHIP)}; a named
 * class rather than that inline factory only so the wiring reads self-documenting.
 */
final class DevPrincipalResolver implements PrincipalResolver {

    @Override
    public UserId userId() {
        return DevPrincipal.USER_ID;
    }

    @Override
    public Ownership ownership() {
        return DevPrincipal.OWNERSHIP;
    }
}

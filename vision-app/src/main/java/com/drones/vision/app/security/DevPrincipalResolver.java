package com.drones.vision.app.security;

import com.drones.vision.api.security.PrincipalResolver;
import com.drones.vision.app.devsupport.DevPrincipal;
import com.drones.vision.application.scope.VisibilityScope;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.UserId;

/**
 * The {@link PrincipalResolver} wired when {@code vision.auth.enabled=false} (the default) — every
 * request is the fixed {@link DevPrincipal}, exactly the pre-auth behavior (docs/U-AUTH-PLAN.md,
 * wave 3). Identical in effect to {@code PrincipalResolver.fixed(DevPrincipal.OWNERSHIP)}; a named
 * class rather than that inline factory only so the wiring reads self-documenting.
 *
 * <p>{@link #scope()} returns {@link VisibilityScope#unbounded()} — the slice-2 guardrail
 * (docs/U-SCOPE-PLAN.md): with auth off, every scoped read/command sees everything, so the
 * default-off build behaves exactly as it does today.
 */
public final class DevPrincipalResolver implements PrincipalResolver {

    @Override
    public UserId userId() {
        return DevPrincipal.USER_ID;
    }

    @Override
    public Ownership ownership() {
        return DevPrincipal.OWNERSHIP;
    }

    @Override
    public VisibilityScope scope() {
        return VisibilityScope.unbounded();
    }
}

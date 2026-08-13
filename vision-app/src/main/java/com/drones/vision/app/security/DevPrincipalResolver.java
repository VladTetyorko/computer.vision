package com.drones.vision.app.security;

import com.drones.vision.api.security.PrincipalResolver;
import com.drones.vision.app.devsupport.DevPrincipal;
import com.drones.vision.map.application.MapAccessPolicy;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.kernel.UserId;

import java.util.Set;

/**
 * The {@link PrincipalResolver} wired when {@code vision.auth.enabled=false} (the default) — every
 * request is the fixed {@link DevPrincipal}, exactly the pre-auth behavior (docs/plans/done/U-AUTH-PLAN.md,
 * wave 3). Identical in effect to {@code PrincipalResolver.fixed(DevPrincipal.OWNERSHIP)}; a named
 * class rather than that inline factory only so the wiring reads self-documenting.
 *
 * <p>{@link #scope()} returns {@link VisibilityScope#unbounded()} — the slice-2 guardrail
 * (docs/plans/done/U-SCOPE-PLAN.md): with auth off, every scoped read/command sees everything, so the
 * default-off build behaves exactly as it does today.
 *
 * <p>{@link #viewer()} is the map-side counterpart of that same guardrail
 * (docs/plans/done/MAP-REWORK-PLAN.md §4): the dev principal is an {@link Role#ADMIN} viewer, which {@link
 * MapAccessPolicy} grants {@code MANAGE} on every layer — so with auth off the whole common
 * operational picture (every layer, every mark, every drawing, and every {@code map} SSE event) is
 * visible, matching the unscoped behavior the marks stack had before layers existed.
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

    @Override
    public MapAccessPolicy.Viewer viewer() {
        return new MapAccessPolicy.Viewer(DevPrincipal.USER_ID, Set.of(DevPrincipal.GROUP_ID), Role.ADMIN);
    }
}

package com.drones.vision.app.config;

import com.drones.vision.api.PrincipalResolver;
import com.drones.vision.app.VisionUserDetails;
import com.drones.vision.application.ScopeResolver;
import com.drones.vision.application.VisibilityScope;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.UserId;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Objects;

/**
 * The {@link PrincipalResolver} wired when {@code vision.auth.enabled=true} — reads the
 * authenticated {@link VisionUserDetails} from Spring Security's {@link SecurityContextHolder}
 * afresh on every call (docs/U-AUTH-PLAN.md, wave 3), so a singleton {@code CurrentUser} still
 * answers per-request and picks up the session's current principal.
 *
 * <p>Every {@code /api/**} path that reads {@code CurrentUser} is already behind the enabled filter
 * chain's {@code authenticated()} rule, so there is always a {@link VisionUserDetails} here in
 * practice; the {@link IllegalStateException} guard is purely defensive (a controller reachable
 * unauthenticated must never silently act as some default user).
 *
 * <p><strong>Scope (docs/U-SCOPE-PLAN.md, U-e slice 2).</strong> {@link #scope()} delegates to
 * {@link ScopeResolver}, computing the acting user's {@link VisibilityScope} from their memberships,
 * the group tree, and their assignments. It is recomputed per call rather than cached per request —
 * acceptable for now (a controller reads {@code scope()} at most a handful of times per request, and
 * the resolver's ports are cheap in-memory/indexed lookups); a per-request cache is a small
 * follow-up if a hot path ever reads it repeatedly. The {@code User} is taken straight from the
 * session principal ({@link VisionUserDetails#user()}), so resolving a scope needs no extra
 * repository hit beyond the group/assignment reads {@code ScopeResolver} itself does.
 */
final class SecurityContextPrincipalResolver implements PrincipalResolver {

    private final ScopeResolver scopeResolver;

    SecurityContextPrincipalResolver(ScopeResolver scopeResolver) {
        this.scopeResolver = Objects.requireNonNull(scopeResolver, "scopeResolver must not be null");
    }

    @Override
    public UserId userId() {
        return principal().user().id();
    }

    @Override
    public Ownership ownership() {
        return principal().ownership();
    }

    @Override
    public VisibilityScope scope() {
        return scopeResolver.scopeFor(principal().user());
    }

    private VisionUserDetails principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof VisionUserDetails details)) {
            throw new IllegalStateException("no authenticated principal on the current request");
        }
        return details;
    }
}

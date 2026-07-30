package com.drones.vision.app;

import com.drones.vision.api.PrincipalResolver;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.UserId;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

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
 */
final class SecurityContextPrincipalResolver implements PrincipalResolver {

    @Override
    public UserId userId() {
        return principal().user().id();
    }

    @Override
    public Ownership ownership() {
        return principal().ownership();
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

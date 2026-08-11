package com.drones.vision.app.security;

import com.drones.vision.api.security.PrincipalResolver;
import com.drones.vision.application.map.MapAccessPolicy;
import com.drones.vision.application.scope.ScopeResolver;
import com.drones.vision.application.scope.VisibilityScope;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.identity.domain.model.Membership;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.identity.domain.model.User;
import com.drones.vision.kernel.UserId;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * The {@link PrincipalResolver} wired when {@code vision.auth.enabled=true} — reads the
 * authenticated {@link VisionUserDetails} from Spring Security's {@link SecurityContextHolder}
 * afresh on every call (docs/plans/done/U-AUTH-PLAN.md, wave 3), so a singleton {@code CurrentUser} still
 * answers per-request and picks up the session's current principal.
 *
 * <p>Every {@code /api/**} path that reads {@code CurrentUser} is already behind the enabled filter
 * chain's {@code authenticated()} rule, so there is always a {@link VisionUserDetails} here in
 * practice; the {@link IllegalStateException} guard is purely defensive (a controller reachable
 * unauthenticated must never silently act as some default user).
 *
 * <p><strong>Scope (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2).</strong> {@link #scope()} delegates to
 * {@link ScopeResolver}, computing the acting user's {@link VisibilityScope} from their memberships,
 * the group tree, and their assignments. It is recomputed per call rather than cached per request —
 * acceptable for now (a controller reads {@code scope()} at most a handful of times per request, and
 * the resolver's ports are cheap in-memory/indexed lookups); a per-request cache is a small
 * follow-up if a hot path ever reads it repeatedly. The {@code User} is taken straight from the
 * session principal ({@link VisionUserDetails#user()}), so resolving a scope needs no extra
 * repository hit beyond the group/assignment reads {@code ScopeResolver} itself does.
 *
 * <p><strong>Map viewer (docs/plans/done/MAP-REWORK-PLAN.md §4).</strong> {@link #viewer()} deliberately does
 * <em>not</em> walk the group tree itself. {@link ScopeResolver} already owns that traversal
 * ({@code DefaultScopeResolver#subtreeOf}, breadth-first with cycle-breaking), and for a MANAGER its
 * result — {@code scope().groups()} — <em>is</em> the expanded subtree. So the viewer's group set is
 * simply the user's own direct memberships unioned with whatever {@link ScopeResolver} resolved:
 * one traversal, one implementation, reused rather than duplicated. The union matters in both
 * directions: an ADMIN resolves to {@code UNBOUNDED} (no groups at all, but {@link Role#ADMIN}
 * already grants MANAGE everywhere), and a PILOT resolves to {@code ASSIGNED_ASSETS} (no groups
 * either), so their direct memberships are the only thing that makes their own team's layer
 * reachable — exactly the trap {@link MapAccessPolicy}'s javadoc warns about.
 */
public final class SecurityContextPrincipalResolver implements PrincipalResolver {

    private final ScopeResolver scopeResolver;

    public SecurityContextPrincipalResolver(ScopeResolver scopeResolver) {
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

    @Override
    public MapAccessPolicy.Viewer viewer() {
        User user = principal().user();
        Set<GroupId> groups = new LinkedHashSet<>();
        user.memberships().stream().map(Membership::groupId).forEach(groups::add);
        groups.addAll(scopeResolver.scopeFor(user).groups());
        return new MapAccessPolicy.Viewer(user.id(), groups, topRoleOf(user));
    }

    /**
     * The highest {@link Role} the user holds anywhere, or {@link Role#PILOT} when they hold none —
     * {@link MapAccessPolicy.Viewer#topRole()} is non-nullable and the least-privileged role is the
     * honest answer for a membership-less account.
     */
    private static Role topRoleOf(User user) {
        return user.memberships().stream()
                .map(Membership::role)
                .max(Comparator.naturalOrder())
                .orElse(Role.PILOT);
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

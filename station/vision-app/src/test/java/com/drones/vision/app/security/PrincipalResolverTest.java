package com.drones.vision.app.security;

import com.drones.vision.api.security.PrincipalResolver;
import com.drones.vision.app.devsupport.DevPrincipal;
import com.drones.vision.identity.application.scope.RoleAuthority;
import com.drones.vision.identity.application.scope.ScopeResolver;
import com.drones.vision.platform.Authority;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.identity.domain.model.Membership;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.identity.domain.model.User;
import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both {@link PrincipalResolver} implementations (docs/plans/done/U-AUTH-PLAN.md, wave 3; scope added
 * docs/plans/done/U-SCOPE-PLAN.md, slice 2): the dev resolver (auth disabled) returns {@link DevPrincipal} and
 * an unbounded scope; the security resolver (auth enabled) returns the authenticated user's
 * id/ownership from the {@link SecurityContextHolder} and delegates scope to {@link ScopeResolver}.
 */
class PrincipalResolverTest {

    /** A scope resolver that records the user it was asked about and returns a fixed marker scope. */
    private static final class RecordingScopeResolver implements ScopeResolver {
        private User asked;
        private final VisibilityScope answer;

        RecordingScopeResolver(VisibilityScope answer) {
            this.answer = answer;
        }

        @Override
        public VisibilityScope scopeFor(User user) {
            this.asked = user;
            return answer;
        }

        @Override
        public Authority authorityFor(User user) {
            return new Authority(scopeFor(user), RoleAuthority.capabilitiesOf(
                    user.topRole().orElse(Role.VIEWER)));
        }
    }

    /** Mirrors {@code DefaultScopeResolver}'s ADMIN/unbounded branch — not under test here. */
    private static final ScopeResolver UNBOUNDED_RESOLVER = new ScopeResolver() {
        @Override
        public VisibilityScope scopeFor(User user) {
            return VisibilityScope.unbounded();
        }

        @Override
        public Authority authorityFor(User user) {
            return Authority.full();
        }
    };

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void devResolverReturnsTheFixedDevPrincipal() {
        PrincipalResolver resolver = new DevPrincipalResolver();
        assertEquals(DevPrincipal.USER_ID, resolver.userId());
        assertEquals(DevPrincipal.OWNERSHIP, resolver.ownership());
    }

    @Test
    void devResolverScopeIsUnbounded() {
        assertTrue(new DevPrincipalResolver().scope().isUnbounded(),
                "auth-off dev principal must resolve to an unbounded scope — the slice-2 guardrail");
    }

    @Test
    void securityResolverReturnsTheAuthenticatedUsersIdAndOwnership() {
        GroupId group = GroupId.random();
        User user = new User(UserId.random(), "op", "Operator", "op@vision.local", "hash", true, false,
                List.of(new Membership(group, Role.MANAGER)));
        authenticate(new VisionUserDetails(user));

        PrincipalResolver resolver = new SecurityContextPrincipalResolver(UNBOUNDED_RESOLVER);
        assertEquals(user.id(), resolver.userId());
        assertEquals(user.id(), resolver.ownership().ownerId());
        assertEquals(group, resolver.ownership().groupId());
    }

    @Test
    void securityResolverDelegatesScopeToTheScopeResolverForTheSessionUser() {
        User user = new User(UserId.random(), "pilot", "Pilot", "pilot@vision.local", "hash", true, false,
                List.of(new Membership(GroupId.random(), Role.PILOT)));
        authenticate(new VisionUserDetails(user));
        VisibilityScope marker = VisibilityScope.assignedAssets(Set.of(AssetId.random()));
        RecordingScopeResolver scopeResolver = new RecordingScopeResolver(marker);

        PrincipalResolver resolver = new SecurityContextPrincipalResolver(scopeResolver);

        assertSame(marker, resolver.scope());
        assertEquals(user, scopeResolver.asked, "the session's own User must be handed to the ScopeResolver");
    }

    @Test
    void securityResolverFallsBackToAPersonalGroupWhenTheUserHasNoMembership() {
        User user = new User(UserId.random(), "loner", "Loner", "loner@vision.local", "hash", true, false,
                List.of());
        authenticate(new VisionUserDetails(user));

        PrincipalResolver resolver = new SecurityContextPrincipalResolver(UNBOUNDED_RESOLVER);
        // personal group == the user's own id (documented fallback rule)
        assertEquals(user.id().value(), resolver.ownership().groupId().value());
    }

    @Test
    void securityResolverThrowsWhenNoAuthenticatedPrincipal() {
        PrincipalResolver resolver = new SecurityContextPrincipalResolver(UNBOUNDED_RESOLVER);
        assertThrows(IllegalStateException.class, resolver::userId);
    }

    private static void authenticate(VisionUserDetails principal) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.authorities()));
        SecurityContextHolder.setContext(context);
    }
}

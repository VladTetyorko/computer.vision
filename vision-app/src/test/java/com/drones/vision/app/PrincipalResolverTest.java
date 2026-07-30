package com.drones.vision.app;

import com.drones.vision.api.PrincipalResolver;
import com.drones.vision.app.devsupport.DevPrincipal;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.Membership;
import com.drones.vision.domain.model.Role;
import com.drones.vision.domain.model.User;
import com.drones.vision.domain.model.UserId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Both {@link PrincipalResolver} implementations (docs/U-AUTH-PLAN.md, wave 3): the dev resolver
 * (auth disabled) returns {@link DevPrincipal}; the security resolver (auth enabled) returns the
 * authenticated user's id/ownership from the {@link SecurityContextHolder}.
 */
class PrincipalResolverTest {

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
    void securityResolverReturnsTheAuthenticatedUsersIdAndOwnership() {
        GroupId group = GroupId.random();
        User user = new User(UserId.random(), "op", "Operator", "op@vision.local", "hash", true,
                List.of(new Membership(group, Role.MANAGER)));
        authenticate(new VisionUserDetails(user));

        PrincipalResolver resolver = new SecurityContextPrincipalResolver();
        assertEquals(user.id(), resolver.userId());
        assertEquals(user.id(), resolver.ownership().ownerId());
        assertEquals(group, resolver.ownership().groupId());
    }

    @Test
    void securityResolverFallsBackToAPersonalGroupWhenTheUserHasNoMembership() {
        User user = new User(UserId.random(), "loner", "Loner", "loner@vision.local", "hash", true, List.of());
        authenticate(new VisionUserDetails(user));

        PrincipalResolver resolver = new SecurityContextPrincipalResolver();
        // personal group == the user's own id (documented fallback rule)
        assertEquals(user.id().value(), resolver.ownership().groupId().value());
    }

    @Test
    void securityResolverThrowsWhenNoAuthenticatedPrincipal() {
        PrincipalResolver resolver = new SecurityContextPrincipalResolver();
        assertThrows(IllegalStateException.class, resolver::userId);
    }

    private static void authenticate(VisionUserDetails principal) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.authorities()));
        SecurityContextHolder.setContext(context);
    }
}

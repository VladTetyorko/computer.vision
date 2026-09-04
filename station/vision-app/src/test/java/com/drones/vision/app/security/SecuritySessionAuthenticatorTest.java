package com.drones.vision.app.security;

import com.drones.vision.app.config.properties.VisionAuthProperties;
import com.drones.vision.identity.application.AuthService;
import com.drones.vision.identity.domain.model.User;
import com.drones.vision.kernel.UserId;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * docs/plans/active/AUTH-ROLES-PLAN.md §3.6, wave B5: session-id rotation on login (fixation
 * protection, since this login flow bypasses Spring Security's own filter-based authentication and
 * therefore never gets {@code ChangeSessionIdAuthenticationStrategy} for free) and the idle/kiosk
 * timeouts now coming from {@link VisionAuthProperties} instead of two bare {@code @Value}s.
 */
class SecuritySessionAuthenticatorTest {

    private static final VisionAuthProperties PROPERTIES = new VisionAuthProperties(
            new VisionAuthProperties.Session(Duration.ofHours(1), Duration.ofDays(30), false));

    private final AuthService authService = mock(AuthService.class);
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();
    private final SecuritySessionAuthenticator authenticator =
            new SecuritySessionAuthenticator(authService, securityContextRepository, PROPERTIES);

    private static User aUser() {
        return new User(UserId.random(), "op", "Operator", "op@vision.local", "hash", true);
    }

    @Test
    void loginRotatesTheSessionId() {
        when(authService.authenticate("op", "secret")).thenReturn(Optional.of(aUser()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String preLoginSessionId = request.getSession(true).getId();

        authenticator.login("op", "secret", false, request, response);

        HttpSession postLoginSession = request.getSession(false);
        assertNotNull(postLoginSession, "login must leave a session behind");
        assertNotEquals(preLoginSessionId, postLoginSession.getId(),
                "login must rotate the session id — the fixation-protection guarantee this class exists to provide");
    }

    @Test
    void loginPersistsTheAuthenticatedPrincipalIntoTheNewSessionId() {
        User user = aUser();
        when(authService.authenticate("op", "secret")).thenReturn(Optional.of(user));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        Optional<User> result = authenticator.login("op", "secret", false, request, response);

        assertEquals(Optional.of(user), result);
        // The context saved is readable back through the SAME repository against the (rotated) request/session —
        // proving the SecurityContext was written to the post-rotation session, not a stale pre-rotation one.
        var readBack = securityContextRepository.loadDeferredContext(request).get();
        assertEquals(user.id(), ((VisionUserDetails) readBack.getAuthentication().getPrincipal()).user().id());
    }

    @Test
    void normalLoginGetsTheIdleTimeoutFromProperties() {
        when(authService.authenticate("op", "secret")).thenReturn(Optional.of(aUser()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        authenticator.login("op", "secret", false, request, response);

        assertEquals((int) PROPERTIES.session().idleTimeout().toSeconds(),
                request.getSession(false).getMaxInactiveInterval());
    }

    @Test
    void kioskLoginGetsTheKioskIdleTimeoutFromProperties() {
        when(authService.authenticate("op", "secret")).thenReturn(Optional.of(aUser()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        authenticator.login("op", "secret", true, request, response);

        assertEquals((int) PROPERTIES.session().kioskIdleTimeout().toSeconds(),
                request.getSession(false).getMaxInactiveInterval());
    }

    @Test
    void failedLoginReturnsEmptyAndNeverTouchesTheSession() {
        when(authService.authenticate("op", "wrong")).thenReturn(Optional.empty());
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        Optional<User> result = authenticator.login("op", "wrong", false, request, response);

        assertFalse(result.isPresent());
        assertNull(request.getSession(false), "a failed login must not create a session");
    }

    @org.junit.jupiter.api.AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }
}

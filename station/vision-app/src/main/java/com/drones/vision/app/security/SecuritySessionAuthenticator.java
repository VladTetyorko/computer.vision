package com.drones.vision.app.security;

import com.drones.vision.api.security.SessionAuthenticator;
import com.drones.vision.identity.application.AuthService;
import com.drones.vision.identity.domain.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;

import java.util.Objects;
import java.util.Optional;

/**
 * The {@link SessionAuthenticator} wired when {@code vision.auth.enabled=true} (docs/plans/done/U-AUTH-PLAN.md,
 * wave 3).
 *
 * <p>Verifies credentials through the application-layer {@link AuthService} (BCrypt, via {@code
 * PasswordHasherPort} — so this class never touches a password encoder itself), then persists an
 * authenticated {@link org.springframework.security.core.Authentication} into the session via the
 * <em>same</em> {@link SecurityContextRepository} the enabled filter chain reads on later requests
 * ({@link SecurityConfig}). Failed logins return {@link Optional#empty()} with no distinction of
 * cause, matching {@code AuthService}'s no-info-leak contract.
 *
 * <p><strong>Idle timeout (docs/plans/active/AUTH-ROLES-PLAN.md §3.7, wave B3).</strong> A normal
 * login gets the {@code vision.auth.session.idle-timeout-hours} idle window (default 12h); a
 * {@code kiosk} login — already refused by {@link com.drones.vision.api.controller.AuthController}
 * for anything but a {@link com.drones.vision.identity.domain.model.Role#VIEWER} — gets {@code
 * vision.auth.session.kiosk-idle-timeout-days} instead (default 365d), the long-lived window an
 * always-on wall display needs. Bound here as plain {@code @Value} primitives rather than a settings
 * record because this wave has exactly two tunables and one consumer; wave B5 (Spring Session JDBC,
 * cookie policy, session-id rotation) is where the session config surface grows enough to earn a
 * {@code VisionAuthProperties} record, and will fold these two in then.
 */
public final class SecuritySessionAuthenticator implements SessionAuthenticator {

    private final AuthService authService;
    private final SecurityContextRepository securityContextRepository;
    private final long idleTimeoutSeconds;
    private final long kioskIdleTimeoutSeconds;

    public SecuritySessionAuthenticator(AuthService authService, SecurityContextRepository securityContextRepository,
                                        @Value("${vision.auth.session.idle-timeout-hours:12}") long idleTimeoutHours,
                                        @Value("${vision.auth.session.kiosk-idle-timeout-days:365}")
                                        long kioskIdleTimeoutDays) {
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
        this.securityContextRepository = Objects.requireNonNull(securityContextRepository,
                "securityContextRepository must not be null");
        this.idleTimeoutSeconds = idleTimeoutHours * 3600L;
        this.kioskIdleTimeoutSeconds = kioskIdleTimeoutDays * 86_400L;
    }

    @Override
    public Optional<User> login(String username, String password, boolean kiosk, HttpServletRequest request,
                                HttpServletResponse response) {
        Optional<User> authenticated = authService.authenticate(username, password);
        if (authenticated.isEmpty()) {
            return Optional.empty();
        }
        VisionUserDetails principal = new VisionUserDetails(authenticated.get());
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.authorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        HttpSession session = request.getSession(true);
        session.setMaxInactiveInterval((int) (kiosk ? kioskIdleTimeoutSeconds : idleTimeoutSeconds));
        return authenticated;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(request, response, authentication);
    }
}

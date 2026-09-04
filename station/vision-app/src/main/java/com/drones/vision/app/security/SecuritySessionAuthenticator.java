package com.drones.vision.app.security;

import com.drones.vision.api.security.SessionAuthenticator;
import com.drones.vision.app.config.properties.VisionAuthProperties;
import com.drones.vision.identity.application.AuthService;
import com.drones.vision.identity.domain.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
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
 * login gets {@link VisionAuthProperties.Session#idleTimeout()} (default 12h); a {@code kiosk}
 * login — already refused by {@link com.drones.vision.api.controller.AuthController} for anything
 * but a {@link com.drones.vision.identity.domain.model.Role#VIEWER} — gets {@link
 * VisionAuthProperties.Session#kioskIdleTimeout()} instead (default 365d), the long-lived window an
 * always-on wall display needs.
 *
 * <p><strong>Session-id rotation (docs/plans/active/AUTH-ROLES-PLAN.md §3.6, wave B5).</strong> This
 * login flow calls {@link AuthService} directly rather than going through Spring Security's own
 * {@code UsernamePasswordAuthenticationFilter}, so the {@code sessionManagement()} DSL's automatic
 * {@code ChangeSessionIdAuthenticationStrategy} never fires here — fixation protection has to be
 * done by hand. {@link #login} therefore rotates the session id ({@link
 * HttpServletRequest#changeSessionId()}) <em>before</em> writing the {@link SecurityContext},
 * fixing what used to be a real session-fixation gap: a pre-login session id (e.g. one an attacker
 * planted and lured a victim into using) could otherwise be reused, now authenticated, after login.
 * {@code changeSessionId()} requires a session to already exist, hence {@code
 * request.getSession(true)} immediately before it, whether or not one existed already. Spring
 * Session JDBC's {@code SessionRepositoryRequestWrapper} (the request wrapper {@code
 * SessionRepositoryFilter} installs once {@code spring-session-jdbc} is on the classpath and {@code
 * spring.session.store-type=jdbc} is set, see {@code application.yaml}) implements this same method
 * by changing the underlying {@code Session}'s id and re-persisting it — the rotation is real
 * against the JDBC-backed store, not a no-op.
 */
public final class SecuritySessionAuthenticator implements SessionAuthenticator {

    private final AuthService authService;
    private final SecurityContextRepository securityContextRepository;
    private final long idleTimeoutSeconds;
    private final long kioskIdleTimeoutSeconds;

    public SecuritySessionAuthenticator(AuthService authService, SecurityContextRepository securityContextRepository,
                                        VisionAuthProperties authProperties) {
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
        this.securityContextRepository = Objects.requireNonNull(securityContextRepository,
                "securityContextRepository must not be null");
        Objects.requireNonNull(authProperties, "authProperties must not be null");
        this.idleTimeoutSeconds = authProperties.session().idleTimeout().toSeconds();
        this.kioskIdleTimeoutSeconds = authProperties.session().kioskIdleTimeout().toSeconds();
    }

    @Override
    public Optional<User> login(String username, String password, boolean kiosk, HttpServletRequest request,
                                HttpServletResponse response) {
        Optional<User> authenticated = authService.authenticate(username, password);
        if (authenticated.isEmpty()) {
            return Optional.empty();
        }
        // Fixation protection: ensure a session exists, then rotate its id, BEFORE this request is
        // ever associated with an authenticated SecurityContext — see this class's own javadoc.
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        VisionUserDetails principal = new VisionUserDetails(authenticated.get());
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.authorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        session.setMaxInactiveInterval((int) (kiosk ? kioskIdleTimeoutSeconds : idleTimeoutSeconds));
        return authenticated;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(request, response, authentication);
    }
}

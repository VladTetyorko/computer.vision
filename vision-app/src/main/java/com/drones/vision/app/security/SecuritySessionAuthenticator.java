package com.drones.vision.app.security;

import com.drones.vision.api.security.SessionAuthenticator;
import com.drones.vision.application.identity.AuthService;
import com.drones.vision.domain.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
 */
public final class SecuritySessionAuthenticator implements SessionAuthenticator {

    private final AuthService authService;
    private final SecurityContextRepository securityContextRepository;

    public SecuritySessionAuthenticator(AuthService authService, SecurityContextRepository securityContextRepository) {
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
        this.securityContextRepository = Objects.requireNonNull(securityContextRepository,
                "securityContextRepository must not be null");
    }

    @Override
    public Optional<User> login(String username, String password, HttpServletRequest request,
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
        return authenticated;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(request, response, authentication);
    }
}

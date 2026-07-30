package com.drones.vision.app;

import com.drones.vision.api.PrincipalResolver;
import com.drones.vision.api.SessionAuthenticator;
import com.drones.vision.application.AuthService;
import com.drones.vision.application.DefaultAuthService;
import com.drones.vision.application.DefaultGroupService;
import com.drones.vision.application.DefaultUserService;
import com.drones.vision.application.GroupService;
import com.drones.vision.application.UserService;
import com.drones.vision.domain.port.out.GroupRepositoryPort;
import com.drones.vision.domain.port.out.PasswordHasherPort;
import com.drones.vision.domain.port.out.UserRepositoryPort;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Wires identity/auth (docs/U-AUTH-PLAN.md, wave 3) — a separate {@code @Configuration} from
 * {@link WiringConfiguration}, same split-out-by-concern precedent as {@link
 * DiscoveryWiringConfiguration}/{@link PersistenceWiringConfiguration}.
 *
 * <p>The application services ({@link AuthService}/{@link UserService}/{@link GroupService}) and the
 * one {@link PasswordHasherPort} (BCrypt) are wired unconditionally — they exist and behave the
 * same whether or not auth is enabled (the seed runner and the login endpoint use them either way).
 * Only the two request-facing seams are gated on {@code vision.auth.enabled}:
 *
 * <ul>
 *   <li>{@link PrincipalResolver} — {@link DevPrincipalResolver} (fixed dev principal) when
 *       disabled/absent, {@link SecurityContextPrincipalResolver} (reads the session) when
 *       enabled. {@code vision-api}'s {@code CurrentUser} autowires whichever is active.</li>
 *   <li>{@link SessionAuthenticator} — a {@link NoopSessionAuthenticator} when disabled (the
 *       controller short-circuits to the dev admin and never calls it), {@link
 *       SecuritySessionAuthenticator} when enabled.</li>
 * </ul>
 *
 * <p>The repository ports themselves ({@link UserRepositoryPort}/{@link GroupRepositoryPort}) are
 * wired in {@link PersistenceWiringConfiguration} alongside every other port, gated by {@code
 * vision.persistence.enabled} — orthogonal to {@code vision.auth.enabled}.
 */
@Configuration
public class AuthWiringConfiguration {

    /** The one BCrypt {@link PasswordHasherPort} — the only place BCrypt is referenced. */
    @Bean
    public PasswordHasherPort passwordHasherPort() {
        return new BcryptPasswordHasher();
    }

    @Bean
    public AuthService authService(UserRepositoryPort userRepositoryPort, PasswordHasherPort passwordHasherPort) {
        return new DefaultAuthService(userRepositoryPort, passwordHasherPort);
    }

    @Bean
    public UserService userService(UserRepositoryPort userRepositoryPort, PasswordHasherPort passwordHasherPort) {
        return new DefaultUserService(userRepositoryPort, passwordHasherPort);
    }

    @Bean
    public GroupService groupService(GroupRepositoryPort groupRepositoryPort) {
        return new DefaultGroupService(groupRepositoryPort);
    }

    /** First-boot seed of a root group + dev users; a no-op once any user exists (see the class). */
    @Bean
    public ApplicationRunner authSeedRunner(UserService userService, GroupService groupService) {
        return new AuthSeedRunner(userService, groupService);
    }

    /** Dev principal for every request when auth is off (default) — pre-auth behavior unchanged. */
    @Bean
    @ConditionalOnProperty(prefix = "vision.auth", name = "enabled", havingValue = "false", matchIfMissing = true)
    public PrincipalResolver devPrincipalResolver() {
        return new DevPrincipalResolver();
    }

    /** Reads the authenticated session's principal when auth is on. */
    @Bean
    @ConditionalOnProperty(prefix = "vision.auth", name = "enabled", havingValue = "true")
    public PrincipalResolver securityContextPrincipalResolver() {
        return new SecurityContextPrincipalResolver();
    }

    /** No-op session seam when auth is off — the controller never calls it in that mode. */
    @Bean
    @ConditionalOnProperty(prefix = "vision.auth", name = "enabled", havingValue = "false", matchIfMissing = true)
    public SessionAuthenticator noopSessionAuthenticator() {
        return new NoopSessionAuthenticator();
    }

    /** Real session establishment when auth is on — verifies via {@link AuthService}, persists to session. */
    @Bean
    @ConditionalOnProperty(prefix = "vision.auth", name = "enabled", havingValue = "true")
    public SessionAuthenticator securitySessionAuthenticator(AuthService authService,
                                                             SecurityContextRepository securityContextRepository) {
        return new SecuritySessionAuthenticator(authService, securityContextRepository);
    }
}

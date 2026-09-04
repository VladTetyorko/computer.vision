package com.drones.vision.app.config.wiring;

import com.drones.vision.identity.domain.port.AssignmentRepositoryPort;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.identity.domain.port.GroupRepositoryPort;
import com.drones.vision.identity.domain.port.PasswordHasherPort;
import com.drones.vision.identity.domain.port.UserRepositoryPort;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.api.security.PrincipalResolver;
import com.drones.vision.api.security.SessionAuthenticator;
import com.drones.vision.app.security.BcryptPasswordHasher;
import com.drones.vision.app.security.DevPrincipalResolver;
import com.drones.vision.app.security.NoopSessionAuthenticator;
import com.drones.vision.app.security.SecurityContextPrincipalResolver;
import com.drones.vision.app.security.SecuritySessionAuthenticator;
import com.drones.vision.identity.application.*;
import com.drones.vision.identity.application.scope.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Wires identity/auth (docs/plans/done/U-AUTH-PLAN.md, wave 3) — a separate {@code @Configuration} from
 * {@code ApplicationServiceWiring}, same split-out-by-concern precedent as {@link
 * DiscoveryWiringConfiguration}/{@link PersistenceWiringConfiguration} (docs/plans/active/LAYERING-REFACTOR-PLAN.md
 * wave D moved all four into this {@code wiring} package alongside {@code
 * VideoSourceWiring}/{@code TelemetryWiring}/{@code PublishWiring}/{@code CvWiring}/{@code
 * ApplicationServiceWiring}/{@code FeedTransmitterWiring}, the pieces {@code WiringConfiguration}'s
 * 44 beans split into).
 *
 * <p>The application services ({@link AuthService}/{@link UserService}/{@link GroupService}) and the
 * one {@link PasswordHasherPort} (BCrypt) are wired unconditionally — they exist and behave the
 * same whether or not auth is enabled (the login endpoint uses them either way). Dev-account
 * seeding is no longer part of this wiring at all: docs/plans/done/POSTGRES-ONLY-CONTEXT.md W1 deleted the
 * code-based {@code AuthSeedRunner} (which used to mint a random root group id here — the root cause
 * of an empty-fleet bug once a MANAGER's scope no longer matched it) in favor of a Flyway migration
 * (`db/seed/dev`, `storage/persistence`) that seeds the fixed-id root group and three DEV-ONLY
 * accounts only when {@code vision.persistence.seed-dev-users=true} — see
 * {@code VisionPersistenceProperties}/{@code PersistenceUnit}. Only the two request-facing seams
 * below are gated on {@code vision.auth.enabled}:
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
 * wired unconditionally in {@link PersistenceWiringConfiguration} alongside every other port
 * (docs/plans/done/POSTGRES-ONLY-CONTEXT.md W2b removed the {@code vision.persistence.enabled} flag
 * that used to gate them) — orthogonal to {@code vision.auth.enabled}.
 */
@Configuration
public class AuthWiringConfiguration {

    /** The one BCrypt {@link PasswordHasherPort} — the only place BCrypt is referenced. */
    @Bean
    public PasswordHasherPort passwordHasherPort() {
        return new BcryptPasswordHasher();
    }

    @Bean
    public AuthService authService(UserRepositoryPort userRepositoryPort, PasswordHasherPort passwordHasherPort,
                                   AuditTrailPort auditTrailPort) {
        return new DefaultAuthService(userRepositoryPort, passwordHasherPort, auditTrailPort);
    }

    @Bean
    public UserService userService(UserRepositoryPort userRepositoryPort, PasswordHasherPort passwordHasherPort,
                                   AuditTrailPort auditTrailPort) {
        return new DefaultUserService(userRepositoryPort, passwordHasherPort, auditTrailPort);
    }

    @Bean
    public GroupService groupService(GroupRepositoryPort groupRepositoryPort) {
        return new DefaultGroupService(groupRepositoryPort);
    }

    /**
     * Visibility-scope resolution (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2) — turns the acting user's
     * memberships + the group tree + their pilot assignments into a {@link
     * com.drones.vision.platform.VisibilityScope}. Wired unconditionally (auth on or off): {@link
     * SecurityContextPrincipalResolver} uses it when auth is on; the {@code DevPrincipalResolver}
     * short-circuits to unbounded without touching it when auth is off.
     */
    @Bean
    public ScopeResolver scopeResolver(GroupRepositoryPort groupRepositoryPort,
                                       AssignmentRepositoryPort assignmentRepositoryPort) {
        return new DefaultScopeResolver(groupRepositoryPort, assignmentRepositoryPort);
    }

    /** Pilot→asset assignment roster (docs/plans/done/U-SCOPE-PLAN.md, feature 2) — behind the assignment endpoints. */
    @Bean
    public AssignmentService assignmentService(AssignmentRepositoryPort assignmentRepositoryPort,
                                               AssetService assetService, AuditTrailPort auditTrailPort) {
        return new DefaultAssignmentService(assignmentRepositoryPort, assetService, auditTrailPort);
    }

    /** A user's own activity feed (docs/plans/done/U-SCOPE-PLAN.md, feature 7) — behind {@code GET /api/me/activity}. */
    @Bean
    public ActivityService activityService(AuditTrailPort auditTrailPort) {
        return new DefaultActivityService(auditTrailPort);
    }

    /** Dev principal for every request when auth is off (default) — pre-auth behavior unchanged. */
    @Bean
    @ConditionalOnProperty(prefix = "vision.auth", name = "enabled", havingValue = "false", matchIfMissing = true)
    public PrincipalResolver devPrincipalResolver() {
        return new DevPrincipalResolver();
    }

    /** Reads the authenticated session's principal when auth is on, resolving its scope via {@link ScopeResolver}. */
    @Bean
    @ConditionalOnProperty(prefix = "vision.auth", name = "enabled", havingValue = "true")
    public PrincipalResolver securityContextPrincipalResolver(ScopeResolver scopeResolver) {
        return new SecurityContextPrincipalResolver(scopeResolver);
    }

    /** No-op session seam when auth is off — the controller never calls it in that mode. */
    @Bean
    @ConditionalOnProperty(prefix = "vision.auth", name = "enabled", havingValue = "false", matchIfMissing = true)
    public SessionAuthenticator noopSessionAuthenticator() {
        return new NoopSessionAuthenticator();
    }

    /**
     * Real session establishment when auth is on — verifies via {@link AuthService}, persists to
     * session. The two idle-timeout {@code @Value}s are declared here, not just on {@link
     * SecuritySessionAuthenticator}'s own constructor — Spring only resolves {@code @Value} on a
     * bean it constructs itself via reflection/component-scan, not on a type this factory method
     * builds with a bare {@code new}, so they must be read here and threaded through explicitly.
     */
    @Bean
    @ConditionalOnProperty(prefix = "vision.auth", name = "enabled", havingValue = "true")
    public SessionAuthenticator securitySessionAuthenticator(AuthService authService,
                                                             SecurityContextRepository securityContextRepository,
                                                             @Value("${vision.auth.session.idle-timeout-hours:12}")
                                                             long idleTimeoutHours,
                                                             @Value("${vision.auth.session.kiosk-idle-timeout-days:365}")
                                                             long kioskIdleTimeoutDays) {
        return new SecuritySessionAuthenticator(authService, securityContextRepository, idleTimeoutHours,
                kioskIdleTimeoutDays);
    }
}

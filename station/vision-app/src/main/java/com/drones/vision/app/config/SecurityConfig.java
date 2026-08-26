package com.drones.vision.app.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Spring Security wiring (docs/plans/done/U-AUTH-PLAN.md, wave 3; default flipped by
 * docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R7), gated entirely by {@code
 * vision.auth.enabled}. Exactly one {@link SecurityFilterChain} bean is ever active, so Spring
 * Boot's own default (HTTP Basic + a generated password) never applies.
 *
 * <h2>Enabled (default) — {@link #securedFilterChain}</h2>
 * A session is required for {@code /api/**} except {@code /api/auth/login}/{@code /api/auth/logout}
 * (so an unauthenticated {@code GET /api/auth/me} is answered {@code 401} by Spring Security, per
 * the frozen contract); static assets and SPA routes stay public. The authenticated principal is
 * read from / written to the session via {@link #securityContextRepository} — the same bean {@link
 * SecuritySessionAuthenticator} saves the context into on login, so a subsequent request resolves
 * the same identity. This is now the shipped default: a deployment that never sets {@code
 * vision.auth.enabled} gets real access control, not {@code anyRequest().permitAll()}. The
 * platform's stated deployment target is running on different servers operators control themselves
 * (CLAUDE.md, "Deployment maintenance"), so the default that ships to all of them must be the safe
 * one — a forgotten flag must not mean no access control at all.
 *
 * <h2>Disabled — {@link #permitAllFilterChain}</h2>
 * Permit-all, CSRF off: <strong>nothing is secured</strong>. This is the explicit dev/demo escape
 * hatch ({@code vision.auth.enabled=false}) for a local run or a demo stack with no login flow set
 * up — it turns off session-required access control on {@code /api/**}/{@code /ws/**} entirely, so
 * every request is answered as the fixed dev principal (see {@code DevPrincipalResolver}) with an
 * unbounded {@code VisibilityScope}, exactly as if auth had never been built
 * (docs/plans/done/U-AUTH-PLAN.md's prime directive, now opt-in rather than the default). CSRF is
 * disabled here too — not just in the secured chain — because the real running app <em>does</em>
 * route through this filter when the flag is off, and a default-on CSRF filter would 403 the SPA's
 * own {@code POST /api/*} calls that worked fine pre-auth.
 *
 * <p>{@code /ws/**} (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md §4, R4) is matched alongside {@code /api/**}:
 * the {@code /ws/manual-control} WebSocket upgrade rides the same session cookie and must be
 * authenticated before the handshake completes, so {@code ManualControlHandshakeInterceptor}
 * (vision-api) never has to reject an identity-less connection itself when this chain is active —
 * Spring Security answers the upgrade {@code GET} {@code 401} first. This is the only addition
 * this wave makes to this class, and it <em>tightens</em> access (auth-required) rather than
 * loosening it; the disabled/permit-all chain already covered {@code /ws/**} via its own {@code
 * anyRequest().permitAll()}, so behavior there is unchanged.
 *
 * <h2>CSRF decision</h2>
 * CSRF is <strong>disabled for the API</strong>, deliberately, and documented. The endpoints are
 * JSON-only, same-origin, and ride a {@code SameSite=Lax} session cookie (Spring Boot's servlet
 * default), which blocks the cross-site form-POST vector CSRF tokens defend against; a token-less
 * same-origin session SPA is a common, defensible posture for an internal tool at this slice.
 * Cookie-to-header double-submit ({@code CookieCsrfTokenRepository.withHttpOnlyFalse}) is the
 * recommended hardening when this leaves the internal-tool stage — it was weighed against the
 * chicken-and-egg it adds to a custom JSON login endpoint and deferred, not overlooked.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Active when {@code vision.auth.enabled} is {@code false} — the explicit dev/demo opt-out; permits everything. */
    @Bean
    @ConditionalOnProperty(prefix = "vision.auth", name = "enabled", havingValue = "false")
    public SecurityFilterChain permitAllFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /** Active when {@code vision.auth.enabled} is {@code true} or absent (the default) — session auth required for {@code /api/**}. */
    @Bean
    @ConditionalOnProperty(prefix = "vision.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SecurityFilterChain securedFilterChain(HttpSecurity http,
                                                  SecurityContextRepository securityContextRepository) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/api/auth/logout").permitAll()
                        // Liveness probe (docs/plans/done/SYSTEM-STATUS-PLAN.md §4.4): a container
                        // healthcheck (docker-compose.yml) has no session to authenticate with, and
                        // only `health` is ever exposed here (application.yaml) -- no secret to
                        // protect. Listed explicitly ahead of the /api/** rule below for clarity, even
                        // though /actuator/** would also fall through to anyRequest().permitAll().
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/api/**", "/ws/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable());
        return http.build();
    }

    /**
     * The session-backed {@link SecurityContextRepository} both the secured chain (read side) and
     * {@link SecuritySessionAuthenticator} (write side on login) share, so a login on one request
     * is seen as authenticated on the next. Only present when auth is enabled (the default).
     */
    @Bean
    @ConditionalOnProperty(prefix = "vision.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }
}

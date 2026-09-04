package com.drones.vision.app.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * /{@code /api/auth/bootstrap} (the last added by docs/plans/active/AUTH-ROLES-PLAN.md §3.5, wave
 * B3 — it must be reachable with no session, since it exists precisely for the moment nobody has one
 * yet; guarded instead by {@code AuthService#adminExists()}'s one-way latch)
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
 * own {@code POST /api/*} calls that worked fine pre-auth. Every activation of this bean logs a
 * boot {@code WARN} naming the setting responsible (see {@link #permitAllFilterChain}) — the
 * vision-web SPA shows the human-facing half of the same warning as a red banner
 * ({@code station/vision-web/src/app/app.html}).
 *
 * <p><strong>Honesty note (docs/plans/active/AUTH-ROLES-PLAN.md D1, wave B0a):</strong> the sentence
 * above — "a deployment that never sets {@code vision.auth.enabled} gets real access control" — is
 * true of this class's own compiled default ({@code matchIfMissing = true} on {@link
 * #securedFilterChain}), but it does <em>not</em> describe what a fresh clone of this repo actually
 * boots into: the shipped {@code application.yaml} sets the key explicitly to {@code false},
 * overriding that compiled default, so {@link #permitAllFilterChain} is what activates today. That
 * override is deliberate — see this repo's {@code application.yaml} for why it is not yet safe to
 * flip — but a reader of only this class's javadoc must not come away believing the shipped station
 * is secured by default when it is not.
 *
 * <p>{@code /ws/**} (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md §4, R4) is matched alongside {@code /api/**}:
 * the {@code /ws/manual-control} WebSocket upgrade rides the same session cookie and must be
 * authenticated before the handshake completes, so {@code ManualControlHandshakeInterceptor}
 * (vision-api) never has to reject an identity-less connection itself when this chain is active —
 * Spring Security answers the upgrade {@code GET} {@code 401} first. This tightens access
 * (auth-required) rather than loosening it; the disabled/permit-all chain already covered {@code
 * /ws/**} via its own {@code anyRequest().permitAll()}, so behavior there is unchanged.
 *
 * <p>{@code /hls/**} (docs/plans/active/AUTH-ROLES-PLAN.md D10, wave B4) joins the same rule for the
 * same reason: before this, it fell all the way through to {@code anyRequest().permitAll()} even on
 * the secured chain, so an unauthenticated caller could reach {@code HlsProxyController#proxy} —
 * which fetches upstream mediamtx <em>with this app's own credentialed Basic-auth header
 * attached</em>. Requiring a session here first closes that half of D10; the other half —
 * {@code StreamAccess} no-oping (rather than failing closed) for a stream id that is not currently
 * running, which let even an <em>authenticated</em> caller reach the credentialed proxy for an id
 * their scope was never actually checked against — is {@link
 * com.drones.vision.api.security.StreamAccess#requireVisibleForHlsProxy}, in {@code vision-api}.
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

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * Active when {@code vision.auth.enabled} is {@code false} — the explicit dev/demo opt-out;
     * permits everything. Logs a boot {@code WARN} on every activation (docs/plans/active/AUTH-ROLES-PLAN.md
     * D1, wave B0a) so this station's own log names the setting an operator would need to flip,
     * rather than only the web UI's red banner saying so with no actionable next step.
     */
    @Bean
    @ConditionalOnProperty(prefix = "vision.auth", name = "enabled", havingValue = "false")
    public SecurityFilterChain permitAllFilterChain(HttpSecurity http) throws Exception {
        log.warn("vision.auth.enabled=false -- this station is UNSECURED. Every request resolves to a "
                + "fixed dev administrator with an unbounded VisibilityScope; the web UI shows this as a "
                + "red \"this station is unsecured\" banner. Set vision.auth.enabled=true "
                + "(VISION_AUTH_ENABLED=true) before this station is reachable by anyone not already "
                + "trusted with the whole fleet.");
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
                        .requestMatchers("/api/auth/login", "/api/auth/logout", "/api/auth/bootstrap")
                        .permitAll()
                        // Liveness probe (docs/plans/done/SYSTEM-STATUS-PLAN.md §4.4): a container
                        // healthcheck (docker-compose.yml) has no session to authenticate with, and
                        // only `health` is ever exposed here (application.yaml) -- no secret to
                        // protect. Listed explicitly ahead of the /api/** rule below for clarity, even
                        // though /actuator/** would also fall through to anyRequest().permitAll().
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/api/**", "/ws/**", "/hls/**").authenticated()
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

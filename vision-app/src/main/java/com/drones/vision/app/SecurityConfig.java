package com.drones.vision.app;

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
 * Spring Security wiring (docs/U-AUTH-PLAN.md, wave 3), gated entirely by {@code
 * vision.auth.enabled}. Exactly one {@link SecurityFilterChain} bean is ever active, so Spring
 * Boot's own default (HTTP Basic + a generated password) never applies.
 *
 * <h2>Disabled (default) — {@link #permitAllFilterChain}</h2>
 * Permit-all, CSRF off: <strong>nothing is secured</strong>, so every existing test, every
 * full-context MockMvc smoke test, and the running SPA behave exactly as before auth existed
 * (docs/U-AUTH-PLAN.md's prime directive). CSRF is disabled here too — not just in the secured
 * chain — because the real running app <em>does</em> route through this filter, and a default-on
 * CSRF filter would 403 the SPA's own {@code POST /api/*} calls that worked fine pre-auth.
 *
 * <h2>Enabled — {@link #securedFilterChain}</h2>
 * A session is required for {@code /api/**} except {@code /api/auth/login}/{@code /api/auth/logout}
 * (so an unauthenticated {@code GET /api/auth/me} is answered {@code 401} by Spring Security, per
 * the frozen contract); static assets and SPA routes stay public. The authenticated principal is
 * read from / written to the session via {@link #securityContextRepository} — the same bean {@link
 * SecuritySessionAuthenticator} saves the context into on login, so a subsequent request resolves
 * the same identity.
 *
 * <p>{@code /ws/**} (docs/RC-CONTROL-PHASE1-PLAN.md §4, R4) is matched alongside {@code /api/**}:
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

    /** Active when {@code vision.auth.enabled} is {@code false} or absent — permit everything. */
    @Bean
    @ConditionalOnProperty(prefix = "vision.auth", name = "enabled", havingValue = "false", matchIfMissing = true)
    public SecurityFilterChain permitAllFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /** Active when {@code vision.auth.enabled=true} — session auth required for {@code /api/**}. */
    @Bean
    @ConditionalOnProperty(prefix = "vision.auth", name = "enabled", havingValue = "true")
    public SecurityFilterChain securedFilterChain(HttpSecurity http,
                                                  SecurityContextRepository securityContextRepository) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/api/auth/logout").permitAll()
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
     * is seen as authenticated on the next. Only present when auth is enabled.
     */
    @Bean
    @ConditionalOnProperty(prefix = "vision.auth", name = "enabled", havingValue = "true")
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }
}

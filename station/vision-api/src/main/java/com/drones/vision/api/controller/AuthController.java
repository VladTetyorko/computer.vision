package com.drones.vision.api.controller;

import com.drones.vision.api.exception.KioskNotPermittedException;
import com.drones.vision.api.security.OpenByDesign;
import com.drones.vision.api.dto.LoginRequest;
import com.drones.vision.api.dto.MeResponse;
import com.drones.vision.identity.application.AuthService;
import com.drones.vision.identity.application.GroupService;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.identity.domain.model.Group;
import com.drones.vision.identity.domain.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.security.SessionAuthenticator;

/**
 * Driving REST adapter for session auth (docs/plans/done/U-AUTH-PLAN.md, wave 3's frozen wire contract;
 * kiosk logins added by docs/plans/active/AUTH-ROLES-PLAN.md §3.5/§3.7, wave B3): {@code POST
 * /api/auth/login}, {@code POST /api/auth/logout}, {@code GET /api/auth/me}. The bootstrap latch
 * ({@code GET}/{@code POST /api/auth/bootstrap}) and self-service password change ({@code POST
 * /api/auth/password}) are separate controllers ({@link BootstrapController}, {@link
 * AuthPasswordController}) — three distinct concerns this class would otherwise cram past the
 * five-collaborator ceiling (java-clean-code SKILL.md §3).
 *
 * <p><strong>No Spring Security here.</strong> Reading the current identity goes through {@link
 * CurrentUser}; establishing/tearing down a session goes through {@link SessionAuthenticator} —
 * both seams {@code vision-app} implements. This controller only knows {@code authEnabled} (the
 * {@code vision.auth.enabled} property, default {@code true} — matching {@code SecurityConfig}'s own
 * compiled default; docs/plans/active/AUTH-ROLES-PLAN.md D2, wave B3, fixed a stale {@code
 * :false} literal here that used to disagree with it) so it can answer the two modes:
 *
 * <ul>
 *   <li><strong>disabled</strong> (default): every endpoint reports the fixed dev admin with
 *       {@code authEnabled=false}; login/logout are no-ops (a login always "succeeds" as the dev
 *       admin), preserving the pre-auth experience exactly.</li>
 *   <li><strong>enabled</strong>: login verifies credentials and starts a session; {@code me}
 *       reflects the authenticated user. An unauthenticated {@code me} never reaches this
 *       controller — Spring Security answers it {@code 401} (the enabled filter chain requires auth
 *       for {@code /api/**} except {@code /api/auth/login}/{@code logout}).</li>
 * </ul>
 */
@RestController
public class AuthController {

    private final AuthService authService;
    private final GroupService groupService;
    private final CurrentUser currentUser;
    private final SessionAuthenticator sessionAuthenticator;
    private final boolean authEnabled;

    public AuthController(AuthService authService, GroupService groupService, CurrentUser currentUser,
                          SessionAuthenticator sessionAuthenticator,
                          @Value("${vision.auth.enabled:true}") boolean authEnabled) {
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
        this.groupService = Objects.requireNonNull(groupService, "groupService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
        this.sessionAuthenticator = Objects.requireNonNull(sessionAuthenticator,
                "sessionAuthenticator must not be null");
        this.authEnabled = authEnabled;
    }

    /**
     * Logs a user in. When auth is disabled this is a no-op that always returns the dev admin;
     * when enabled it verifies credentials and, on success, issues a session cookie.
     *
     * <p>{@code request.kiosk()} (docs/plans/active/AUTH-ROLES-PLAN.md §3.5/§3.7, wave B3) requests a
     * long-lived session for an always-on wall display; only a login that resolves to {@link
     * Role#VIEWER} may request it. Credentials are verified exactly once regardless of {@code kiosk}
     * — checking role by a separate, unauthenticated username lookup before verifying the password
     * would let a caller learn "this account is not a VIEWER" without ever proving they hold its
     * password, the same info-leak {@link AuthService#authenticate}'s own contract forbids. So a
     * kiosk request for a non-{@code VIEWER} account is instead caught <em>after</em> a real,
     * successful login: the just-established session is immediately torn down
     * ({@link SessionAuthenticator#logout}) and {@code 400 KIOSK_NOT_PERMITTED} is returned. A wrong
     * password still answers plain {@code 401}, kiosk or not — the role check never runs for a
     * credential that did not verify.
     *
     * @param request  the login credentials
     * @param httpRequest  current request (session established on it)
     * @param httpResponse current response (session cookie written to it)
     * @return {@code 200} + {@link MeResponse} on success; {@code 401} on bad credentials; {@code
     *         400 KIOSK_NOT_PERMITTED} if a kiosk session was requested for a non-{@code VIEWER}
     */
    @OpenByDesign(reason = "The login endpoint itself — it must be reachable with no session, or nobody can ever get one.")
    @PostMapping("/api/auth/login")
    public ResponseEntity<MeResponse> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest,
                                            HttpServletResponse httpResponse) {
        if (!authEnabled) {
            return ResponseEntity.ok(devAdmin());
        }
        boolean kiosk = request.kioskRequested();
        Optional<User> user = sessionAuthenticator.login(request.username(), request.password(), kiosk,
                httpRequest, httpResponse);
        if (user.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (kiosk && user.get().topRole().orElse(null) != Role.VIEWER) {
            sessionAuthenticator.logout(httpRequest, httpResponse);
            throw new KioskNotPermittedException("kiosk sessions are only permitted for a VIEWER login");
        }
        return ResponseEntity.ok(me(user.get()));
    }

    /**
     * Ends the current session. Always {@code 204}, idempotent.
     *
     * @param httpRequest  current request
     * @param httpResponse current response
     * @return {@code 204 No Content}
     */
    @OpenByDesign(reason = "Ends the caller's own session; owning a session is the only authority it needs.")
    @PostMapping("/api/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        if (authEnabled) {
            sessionAuthenticator.logout(httpRequest, httpResponse);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * The current identity. Dev admin ({@code authEnabled=false}) when auth is disabled; the
     * authenticated user otherwise (Spring Security has already guaranteed one exists here).
     *
     * @return {@code 200} + {@link MeResponse}
     */
    @OpenByDesign(reason = "Returns the caller's own identity, resolved from the session — takes no id and can address nobody else.")
    @GetMapping("/api/auth/me")
    public ResponseEntity<MeResponse> me() {
        if (!authEnabled) {
            return ResponseEntity.ok(devAdmin());
        }
        return authService.find(currentUser.userId())
                .map(u -> ResponseEntity.ok(me(u)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    private MeResponse devAdmin() {
        return MeResponse.devAdmin(currentUser.userId().value().toString(),
                currentUser.ownership().groupId().value().toString());
    }

    private MeResponse me(User user) {
        return MeResponse.from(user, groupNameLookup(), true, currentUser.authority());
    }

    private Function<String, String> groupNameLookup() {
        // Resolve names for the current user's own memberships — a system read that must see every
        // group regardless of the caller's scope, so it is deliberately unbounded.
        Map<String, String> byId = groupService.list(VisibilityScope.unbounded()).stream()
                .collect(Collectors.toMap(g -> g.id().value().toString(), Group::name, (a, b) -> a));
        return byId::get;
    }
}

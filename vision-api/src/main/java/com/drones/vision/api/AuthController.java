package com.drones.vision.api;

import com.drones.vision.api.dto.LoginRequest;
import com.drones.vision.api.dto.MeResponse;
import com.drones.vision.application.AuthService;
import com.drones.vision.application.GroupService;
import com.drones.vision.application.VisibilityScope;
import com.drones.vision.domain.model.Group;
import com.drones.vision.domain.model.User;
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

/**
 * Driving REST adapter for session auth (docs/U-AUTH-PLAN.md, wave 3's frozen wire contract):
 * {@code POST /api/auth/login}, {@code POST /api/auth/logout}, {@code GET /api/auth/me}.
 *
 * <p><strong>No Spring Security here.</strong> Reading the current identity goes through {@link
 * CurrentUser}; establishing/tearing down a session goes through {@link SessionAuthenticator} —
 * both seams {@code vision-app} implements. This controller only knows {@code authEnabled} (the
 * {@code vision.auth.enabled} property) so it can answer the two modes:
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
                          @Value("${vision.auth.enabled:false}") boolean authEnabled) {
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
     * @param request  the login credentials
     * @param httpRequest  current request (session established on it)
     * @param httpResponse current response (session cookie written to it)
     * @return {@code 200} + {@link MeResponse} on success; {@code 401} on bad credentials
     */
    @PostMapping("/api/auth/login")
    public ResponseEntity<MeResponse> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest,
                                            HttpServletResponse httpResponse) {
        if (!authEnabled) {
            return ResponseEntity.ok(devAdmin());
        }
        Optional<User> user = sessionAuthenticator.login(request.username(), request.password(),
                httpRequest, httpResponse);
        return user.map(u -> ResponseEntity.ok(me(u)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    /**
     * Ends the current session. Always {@code 204}, idempotent.
     *
     * @param httpRequest  current request
     * @param httpResponse current response
     * @return {@code 204 No Content}
     */
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
        return MeResponse.from(user, groupNameLookup(), true);
    }

    private Function<String, String> groupNameLookup() {
        // Resolve names for the current user's own memberships — a system read that must see every
        // group regardless of the caller's scope, so it is deliberately unbounded.
        Map<String, String> byId = groupService.list(VisibilityScope.unbounded()).stream()
                .collect(Collectors.toMap(g -> g.id().value().toString(), Group::name, (a, b) -> a));
        return byId::get;
    }
}

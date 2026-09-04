package com.drones.vision.api.controller;

import com.drones.vision.api.dto.BootstrapRequest;
import com.drones.vision.api.dto.BootstrapStatusResponse;
import com.drones.vision.api.dto.MeResponse;
import com.drones.vision.api.exception.AlreadyInitializedException;
import com.drones.vision.api.security.OpenByDesign;
import com.drones.vision.api.security.PasswordPolicy;
import com.drones.vision.api.security.SessionAuthenticator;
import com.drones.vision.identity.application.AuthService;
import com.drones.vision.identity.application.FirstAdminSpec;
import com.drones.vision.identity.application.GroupService;
import com.drones.vision.identity.application.GroupSpec;
import com.drones.vision.identity.application.UserService;
import com.drones.vision.identity.domain.model.Group;
import com.drones.vision.identity.domain.model.User;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.platform.Authority;
import com.drones.vision.platform.VisibilityScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Driving REST adapter for the first-run bootstrap latch (docs/plans/active/AUTH-ROLES-PLAN.md §3.5,
 * wave B3): {@code GET}/{@code POST /api/auth/bootstrap}, both reachable with no session — {@code
 * required} answers whether a station still needs its very first administrator, and {@code POST}
 * creates one exactly once.
 *
 * <p><strong>Collaborator count (java-clean-code SKILL.md §3):</strong> five services plus the
 * {@code vision.auth.enabled} flag is one over the stated five-parameter ceiling. This is a
 * deliberate, disclosed exception rather than an oversight: bootstrapping the first administrator is
 * genuinely one atomic action that must (a) check the one-way latch ({@link AuthService}), (b)
 * create the account ({@link UserService}), (c) resolve or create the fixed root group it belongs to
 * ({@link GroupService}), (d) establish its session on success ({@link SessionAuthenticator}), and
 * (e) enforce the same password floor every other credential-setting endpoint does ({@link
 * PasswordPolicy}) — splitting this across multiple classes would fragment one user-facing action
 * (the first-run setup wizard's single "create my account" step) with no cohesion benefit, and none
 * of the five collaborators is optional or could be dropped without silently skipping a real
 * requirement of the frozen wire contract. Left as a named exception rather than trimmed at the cost
 * of correctness.
 */
@RestController
public class BootstrapController {

    private final AuthService authService;
    private final UserService userService;
    private final GroupService groupService;
    private final SessionAuthenticator sessionAuthenticator;
    private final PasswordPolicy passwordPolicy;
    private final boolean authEnabled;

    public BootstrapController(AuthService authService, UserService userService, GroupService groupService,
                               SessionAuthenticator sessionAuthenticator, PasswordPolicy passwordPolicy,
                               @Value("${vision.auth.enabled:true}") boolean authEnabled) {
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
        this.userService = Objects.requireNonNull(userService, "userService must not be null");
        this.groupService = Objects.requireNonNull(groupService, "groupService must not be null");
        this.sessionAuthenticator =
                Objects.requireNonNull(sessionAuthenticator, "sessionAuthenticator must not be null");
        this.passwordPolicy = Objects.requireNonNull(passwordPolicy, "passwordPolicy must not be null");
        this.authEnabled = authEnabled;
    }

    /**
     * Whether this station still needs its first administrator — a one-way latch: once {@code
     * false}, no path in this application flips it back to {@code true}.
     *
     * @return {@code 200} + {@link BootstrapStatusResponse}
     */
    @OpenByDesign(reason = "Must be reachable with no session — it exists precisely for the moment "
            + "nobody has authority yet.")
    @GetMapping("/api/auth/bootstrap")
    public BootstrapStatusResponse status() {
        return new BootstrapStatusResponse(authEnabled && !authService.adminExists());
    }

    /**
     * Creates the very first administrator, once, and establishes their session.
     *
     * @param request      the new administrator's own chosen credentials
     * @param httpRequest  current request (session established on it)
     * @param httpResponse current response (session cookie written to it)
     * @return {@code 201} + {@link MeResponse} on success
     * @throws AlreadyInitializedException if the latch has already closed ({@code 409
     *                                       ALREADY_INITIALIZED})
     * @throws com.drones.vision.api.exception.WeakPasswordException if the password is too short
     *                                       ({@code 400 WEAK_PASSWORD})
     */
    @OpenByDesign(reason = "Must be reachable with no session — creates the very first account, so "
            + "there is no existing authority to check yet; guarded instead by the one-way "
            + "adminExists() latch.")
    @PostMapping("/api/auth/bootstrap")
    public ResponseEntity<MeResponse> bootstrap(@RequestBody BootstrapRequest request,
                                                HttpServletRequest httpRequest,
                                                HttpServletResponse httpResponse) {
        if (!authEnabled || authService.adminExists()) {
            throw new AlreadyInitializedException("this station already has an administrator");
        }
        passwordPolicy.require(request.password());

        Group rootGroup = resolveRootGroup();
        FirstAdminSpec spec = new FirstAdminSpec(request.username(), request.displayName(), request.email(),
                request.password(), rootGroup.id());
        User admin = userService.createFirstAdmin(spec);

        Optional<User> session = sessionAuthenticator.login(admin.username(), request.password(), false,
                httpRequest, httpResponse);
        User authenticated = session.orElseThrow(
                () -> new IllegalStateException("failed to establish a session for the newly bootstrapped admin"));

        Map<String, String> groupNames = Map.of(rootGroup.id().value().toString(), rootGroup.name());
        MeResponse body = MeResponse.from(authenticated, groupNames, true, Authority.full());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * The fixed root group the first administrator's membership is granted in — reuses an existing
     * root (a group with no parent) if one already exists (e.g. created out-of-band before ever
     * bootstrapping), otherwise creates one named {@code "Root"}.
     */
    private Group resolveRootGroup() {
        List<Group> groups = groupService.list(VisibilityScope.unbounded());
        return groups.stream()
                .filter(g -> g.parentGroupId() == null)
                .findFirst()
                .orElseGet(() -> groupService.create(new GroupSpec("Root", (GroupId) null),
                        VisibilityScope.unbounded()));
    }
}

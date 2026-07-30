package com.drones.vision.api;

import com.drones.vision.api.dto.CreateUserRequest;
import com.drones.vision.api.dto.SetUserEnabledRequest;
import com.drones.vision.api.dto.UserResponse;
import com.drones.vision.application.UserService;
import com.drones.vision.domain.model.UserId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Driving REST adapter for user management (docs/U-SCOPE-PLAN.md, U-e slice 2) — the org-settings
 * surface wave 3's UI needs: list users, create/invite a user, enable/disable one.
 *
 * <p>Constructor-injected with {@link UserService} only — the wave-1 CRUD it delegates to
 * (`create`/`list`/`setEnabled`). No richer method exists yet, so nothing more is exposed here.
 *
 * <h2>Deliberate gaps (documented, not faked)</h2>
 * <ul>
 *   <li><strong>No role gate.</strong> The plan calls these ADMIN/MANAGER-only. Role is not on
 *       {@link CurrentUser} (only {@code userId}/{@code ownership}/{@code scope}), and adding it —
 *       or a Spring-Security method gate — is outside this wave's file scope; with auth off every
 *       caller is the dev admin regardless. Enforcing the role gate is a follow-up.</li>
 *   <li><strong>No &le;-own-scope grant rule.</strong> {@code UserService.create} is unscoped in
 *       wave 1 (documented there as deferred to this slice), and this wave's scope does not extend
 *       into {@code vision-application} to add it. {@code list()} likewise returns all users, not
 *       just those in the caller's visible groups. Both are follow-ups needing application-layer
 *       support.</li>
 * </ul>
 * With auth off (default) the dev admin can do all of this, so the default-off build is unchanged.
 */
@RestController
public class UserAdminController {

    private final UserService userService;

    public UserAdminController(UserService userService) {
        this.userService = Objects.requireNonNull(userService, "userService must not be null");
    }

    /**
     * Lists all users.
     *
     * @return every user
     */
    @GetMapping("/api/users")
    public List<UserResponse> list() {
        return userService.list().stream().map(UserResponse::from).toList();
    }

    /**
     * Creates (invites) a user.
     *
     * @param request the new user's shape
     * @return the created user
     */
    @PostMapping("/api/users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@RequestBody CreateUserRequest request) {
        return UserResponse.from(userService.create(request.toSpec()));
    }

    /**
     * Enables or disables a user's ability to authenticate. Idempotent.
     *
     * @param id      the user, as a canonical UUID string
     * @param request the new enabled state
     * @return the updated user
     */
    @PostMapping("/api/users/{id}/enabled")
    public UserResponse setEnabled(@PathVariable String id, @RequestBody SetUserEnabledRequest request) {
        return UserResponse.from(userService.setEnabled(UserId.of(id), request.enabled()));
    }
}

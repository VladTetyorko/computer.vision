package com.drones.vision.api.controller;

import com.drones.vision.api.dto.CreateUserRequest;
import com.drones.vision.api.dto.SetUserEnabledRequest;
import com.drones.vision.api.dto.UserResponse;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.application.identity.UserService;
import com.drones.vision.kernel.UserId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import com.drones.vision.api.security.CurrentUser;

/**
 * Driving REST adapter for user management (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2) — the org-settings
 * surface wave 3's UI needs: list users, create/invite a user, enable/disable one.
 *
 * <p>Constructor-injected with {@link UserService} and {@link CurrentUser}: every operation passes
 * {@code currentUser.scope()} into the service, which derives management authority from it (kind
 * maps 1:1 to role — unbounded = ADMIN, groups = MANAGER, else PILOT/empty) and enforces the
 * ADMIN/MANAGER management gate plus the ≤-own-scope grant rule. A PILOT/empty scope is refused with
 * {@link com.drones.vision.application.scope.AccessDeniedException} (403 via {@link ApiExceptionHandler});
 * {@code list} is scope-filtered to the caller's own subtree. With auth off (default) the dev
 * principal's scope is unbounded, so every operation is permitted and unfiltered — the default-off
 * build is unchanged.
 */
@RestController
public class UserAdminController {

    private final UserService userService;
    private final CurrentUser currentUser;

    public UserAdminController(UserService userService, CurrentUser currentUser) {
        this.userService = Objects.requireNonNull(userService, "userService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * Lists the users visible to the caller's scope.
     *
     * @return the visible users
     */
    @GetMapping("/api/users")
    public List<UserResponse> list() {
        return userService.list(currentUser.scope()).stream().map(UserResponse::from).toList();
    }

    /**
     * Creates (invites) a user, within the caller's scope.
     *
     * @param request the new user's shape
     * @return the created user
     */
    @PostMapping("/api/users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@RequestBody CreateUserRequest request) {
        return UserResponse.from(userService.create(request.toSpec(), currentUser.scope()));
    }

    /**
     * Enables or disables a user's ability to authenticate, within the caller's scope. Idempotent.
     *
     * @param id      the user, as a canonical UUID string
     * @param request the new enabled state
     * @return the updated user
     */
    @PostMapping("/api/users/{id}/enabled")
    public UserResponse setEnabled(@PathVariable String id, @RequestBody SetUserEnabledRequest request) {
        return UserResponse.from(userService.setEnabled(UserId.of(id), request.enabled(), currentUser.scope()));
    }
}

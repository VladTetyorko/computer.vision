package com.drones.vision.api.controller;

import com.drones.vision.api.dto.AdminSetPasswordRequest;
import com.drones.vision.api.dto.CreateUserRequest;
import com.drones.vision.api.dto.SetMembershipsRequest;
import com.drones.vision.api.dto.SetUserEnabledRequest;
import com.drones.vision.api.dto.UserResponse;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.PasswordPolicy;
import com.drones.vision.identity.application.UserService;
import com.drones.vision.kernel.UserId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import com.drones.vision.api.security.CurrentUser;

/**
 * Driving REST adapter for user management (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2; password
 * reset + membership edit added by docs/plans/active/AUTH-ROLES-PLAN.md D13/D14, wave B3) — the
 * org-settings surface's UI needs: list users, create/invite a user, enable/disable one, reset a
 * password, replace a user's memberships.
 *
 * <p>Constructor-injected with {@link UserService}, {@link CurrentUser}, and (wave B3) {@link
 * PasswordPolicy}: every operation passes {@code currentUser.scope()} (and, since wave B3, {@code
 * currentUser.userId()} as the acting actor for audit attribution) into the service, which derives
 * management authority from the scope (kind maps 1:1 to role — unbounded = ADMIN, groups = MANAGER,
 * else PILOT/empty) and enforces the ADMIN/MANAGER management gate plus the ≤-own-scope grant rule. A
 * PILOT/empty scope is refused with {@link com.drones.vision.platform.AccessDeniedException} (403
 * via {@link ApiExceptionHandler}); {@code list} is scope-filtered to the caller's own subtree. With
 * auth off (default) the dev principal's scope is unbounded, so every operation is permitted and
 * unfiltered — the default-off build is unchanged.
 */
@RestController
public class UserAdminController {

    private final UserService userService;
    private final CurrentUser currentUser;
    private final PasswordPolicy passwordPolicy;

    public UserAdminController(UserService userService, CurrentUser currentUser, PasswordPolicy passwordPolicy) {
        this.userService = Objects.requireNonNull(userService, "userService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
        this.passwordPolicy = Objects.requireNonNull(passwordPolicy, "passwordPolicy must not be null");
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
        return UserResponse.from(userService.create(request.toSpec(), currentUser.userId(), currentUser.scope()));
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
        return UserResponse.from(
                userService.setEnabled(UserId.of(id), request.enabled(), currentUser.userId(), currentUser.scope()));
    }

    /**
     * Admin-resets a user's password, within the caller's scope (docs/plans/active/AUTH-ROLES-PLAN.md D13,
     * wave B3) — always sets {@link com.drones.vision.identity.domain.model.User#mustChangePassword()}
     * on the target, forcing them to choose their own at next login.
     *
     * @param id      the user, as a canonical UUID string
     * @param request the new password
     * @return {@code 204}
     */
    @PostMapping("/api/users/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setPassword(@PathVariable String id, @RequestBody AdminSetPasswordRequest request) {
        passwordPolicy.require(request.newPassword());
        userService.setPassword(UserId.of(id), request.newPassword(), currentUser.userId(), currentUser.scope());
    }

    /**
     * Wholesale-replaces a user's group memberships, within the caller's scope
     * (docs/plans/active/AUTH-ROLES-PLAN.md D14, wave B3).
     *
     * @param id      the user, as a canonical UUID string
     * @param request the complete new membership set
     * @return the updated user
     */
    @PutMapping("/api/users/{id}/memberships")
    public UserResponse setMemberships(@PathVariable String id, @RequestBody SetMembershipsRequest request) {
        return UserResponse.from(userService.setMemberships(UserId.of(id), request.toMemberships(),
                currentUser.userId(), currentUser.scope()));
    }
}

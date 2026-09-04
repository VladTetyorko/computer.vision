package com.drones.vision.api.controller;

import com.drones.vision.api.dto.ChangePasswordRequest;
import com.drones.vision.api.exception.AuthDisabledException;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.security.OpenByDesign;
import com.drones.vision.api.security.PasswordPolicy;
import com.drones.vision.identity.application.AuthService;
import com.drones.vision.identity.domain.model.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.Optional;

/**
 * Driving REST adapter for self-service password change (docs/plans/active/AUTH-ROLES-PLAN.md §3.5,
 * wave B3): {@code POST /api/auth/password}. Split out of {@link AuthController} so that class's
 * constructor does not have to carry this endpoint's own collaborators on top of session auth's
 * (java-clean-code SKILL.md §3's five-parameter ceiling) — session login/logout/me is one concern,
 * a caller re-confirming and changing their own credential is a distinct one.
 *
 * <p>Clears {@link User#mustChangePassword()} on success — the counterpart to {@link
 * UserAdminController}'s admin-reset endpoint, which always sets it.
 */
@RestController
public class AuthPasswordController {

    private final AuthService authService;
    private final CurrentUser currentUser;
    private final PasswordPolicy passwordPolicy;
    private final boolean authEnabled;

    public AuthPasswordController(AuthService authService, CurrentUser currentUser,
                                  PasswordPolicy passwordPolicy,
                                  @Value("${vision.auth.enabled:true}") boolean authEnabled) {
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
        this.passwordPolicy = Objects.requireNonNull(passwordPolicy, "passwordPolicy must not be null");
        this.authEnabled = authEnabled;
    }

    /**
     * Changes the caller's own password, re-confirming their current one.
     *
     * @param request the current and new plaintext passwords
     * @return {@code 204} on success
     * @throws AuthDisabledException                              if {@code vision.auth.enabled=false}
     *                                                             ({@code 409 AUTH_DISABLED})
     * @throws com.drones.vision.api.exception.WeakPasswordException if the new password is too short
     *                                                             ({@code 400 WEAK_PASSWORD})
     */
    @OpenByDesign(reason = "Self-service: changes only the caller's own password, re-confirming their "
            + "own current one — the acting user's own id is the only authority it needs, like /api/auth/logout.")
    @PostMapping("/api/auth/password")
    public ResponseEntity<Void> changePassword(@RequestBody ChangePasswordRequest request) {
        if (!authEnabled) {
            throw new AuthDisabledException("cannot change a password while vision.auth.enabled=false");
        }
        passwordPolicy.require(request.newPassword());
        Optional<User> updated = authService.changePassword(currentUser.userId(), request.currentPassword(),
                request.newPassword());
        if (updated.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.noContent().build();
    }
}

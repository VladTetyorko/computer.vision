package com.drones.vision.app;

import com.drones.vision.api.SessionAuthenticator;
import com.drones.vision.domain.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Optional;

/**
 * The {@link SessionAuthenticator} wired when {@code vision.auth.enabled=false} — a no-op.
 *
 * <p>{@code AuthController} short-circuits to the dev admin in disabled mode and never calls this,
 * so its methods exist only to satisfy the always-present bean dependency (docs/U-AUTH-PLAN.md,
 * wave 3). There is no session to establish or tear down when auth is off.
 */
final class NoopSessionAuthenticator implements SessionAuthenticator {

    @Override
    public Optional<User> login(String username, String password, HttpServletRequest request,
                                HttpServletResponse response) {
        return Optional.empty();
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        // no session when auth is disabled
    }
}

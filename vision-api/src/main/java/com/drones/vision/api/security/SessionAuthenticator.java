package com.drones.vision.api.security;

import com.drones.vision.domain.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Optional;
import com.drones.vision.api.controller.AuthController;

/**
 * The seam {@link AuthController} uses to establish and tear down a login session, kept in {@code
 * vision-api} so the controller never references Spring Security (docs/plans/done/U-AUTH-PLAN.md, wave 3).
 *
 * <p>{@code vision-app} supplies the implementation: when {@code vision.auth.enabled=true} it
 * verifies the credentials (BCrypt, via the application-layer {@code AuthService}) and persists an
 * authenticated Spring Security session to the servlet session; when {@code false} the beans wired
 * for it are a no-op — {@link AuthController} short-circuits to the dev admin without ever calling
 * this seam. It only carries plain {@code jakarta.servlet} + domain types, never a Spring Security
 * one.
 */
public interface SessionAuthenticator {

    /**
     * Verifies {@code username}/{@code password} and, on success, establishes an authenticated
     * session on {@code request}/{@code response} (a session cookie is issued via the response).
     *
     * @param username the attempted username, any casing
     * @param password the attempted plaintext password
     * @param request  the current request (its session is (re)created on success)
     * @param response the current response (the session cookie is written to it)
     * @return the authenticated user on success, or {@link Optional#empty()} on any failed login
     *         (unknown/disabled user or wrong password — indistinguishable, no info leak)
     */
    Optional<User> login(String username, String password, HttpServletRequest request,
                         HttpServletResponse response);

    /**
     * Invalidates the current session and clears the security context. Idempotent — safe to call
     * with no active session.
     *
     * @param request  the current request
     * @param response the current response
     */
    void logout(HttpServletRequest request, HttpServletResponse response);
}

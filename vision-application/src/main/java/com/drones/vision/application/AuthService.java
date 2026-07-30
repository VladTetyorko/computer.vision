package com.drones.vision.application;

import com.drones.vision.domain.model.User;
import com.drones.vision.domain.model.UserId;

import java.util.Optional;

/**
 * Authenticates users and re-resolves an already-authenticated principal (docs/U-AUTH-PLAN.md,
 * wave 2).
 *
 * <p>Two read paths exist for two different callers (wave 3): {@link #find(UserId)} is how a
 * security adapter reloads the current session's principal fresh on every request (a session
 * only needs to remember an id; enabled/role changes since login are picked up immediately, no
 * re-login required), while {@link #loadByUsername(String)} is how Spring Security's own
 * {@code UserDetailsService} bridge looks a user up by the username it always keys principals by.
 * Both are thin pass-throughs, but neither subsumes the other's caller.
 */
public interface AuthService {

    /**
     * Checks a login attempt.
     *
     * <p>Never throws for a normal failed login — a {@code null}/blank {@code username} or
     * {@code rawPassword}, an unknown username, a disabled user, and a wrong password all return
     * {@link Optional#empty()} alike. The caller cannot (and must not attempt to) distinguish
     * which of those happened: revealing "that account is disabled" vs. "wrong password" vs.
     * "no such user" to an unauthenticated caller is an information leak a login endpoint must not
     * have.
     *
     * @param username    the attempted username, in any casing; {@code null}/blank fails the login
     * @param rawPassword the attempted plaintext password; {@code null}/blank fails the login
     * @return the authenticated user iff enabled and the password matches, else
     *         {@link Optional#empty()}
     */
    Optional<User> authenticate(String username, String rawPassword);

    /**
     * Reloads a user by id, for re-resolving an already-authenticated session's principal.
     *
     * @param id the user id
     * @return the user, or {@link Optional#empty()} if none exists (e.g. deleted since login)
     */
    Optional<User> find(UserId id);

    /**
     * Loads a user by username, for Spring Security's {@code UserDetailsService} bridge (wave 3).
     *
     * @param username the username to look up, in any casing
     * @return the user, or {@link Optional#empty()} if none exists
     */
    Optional<User> loadByUsername(String username);
}

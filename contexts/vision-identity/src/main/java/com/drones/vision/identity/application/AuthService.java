package com.drones.vision.identity.application;

import com.drones.vision.identity.domain.model.User;
import com.drones.vision.kernel.UserId;

import java.util.Optional;

/**
 * Authenticates users and re-resolves an already-authenticated principal (docs/plans/done/U-AUTH-PLAN.md,
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

    /**
     * Self-service password change (docs/plans/active/AUTH-ROLES-PLAN.md §3.5, wave B2) — the caller
     * is already an authenticated session and is re-confirming their own current password, not
     * attempting a login, so unlike {@link #authenticate(String, String)} this need not (and does
     * not) hide <em>why</em> it failed.
     *
     * <p>On success, clears {@link User#mustChangePassword()} and records an
     * {@link com.drones.vision.platform.AuditAction#UPDATED} entry
     * ({@link com.drones.vision.platform.AuditTargetType#USER}).
     *
     * @param id              the user changing their own password
     * @param currentPassword the password they claim to currently have
     * @param newPassword     the new plaintext password to hash and store
     * @return the updated user, or {@link Optional#empty()} if {@code id} is unknown, the account is
     *         disabled, or {@code currentPassword} does not match
     */
    Optional<User> changePassword(UserId id, String currentPassword, String newPassword);

    /**
     * Whether any enabled user currently holds an {@link com.drones.vision.identity.domain.model.Role#ADMIN}
     * membership — the one-way latch behind {@code GET /api/auth/bootstrap}'s {@code required} flag
     * (docs/plans/active/AUTH-ROLES-PLAN.md §3.5, wave B2): once true, it is true forever (no path in
     * this application removes every admin).
     *
     * @return {@code true} iff at least one enabled user's {@link User#topRole()} is {@code ADMIN}
     */
    boolean adminExists();
}

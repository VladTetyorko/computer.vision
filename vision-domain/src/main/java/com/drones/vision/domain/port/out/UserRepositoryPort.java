package com.drones.vision.domain.port.out;

import com.drones.vision.domain.model.User;
import com.drones.vision.domain.model.UserId;

import java.util.List;
import java.util.Optional;

/**
 * Driven port: persist and retrieve {@link User}s (docs/plans/done/U-AUTH-PLAN.md, wave 1).
 *
 * <p>The full {@link User} aggregate — including its {@code memberships} — is saved and loaded
 * as a whole; there is no separate membership port.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #findByUsername(String)} matches case-insensitively against the normalized
 *       username every {@link User} already stores lower-cased (see {@link User}'s own javadoc);
 *       callers may pass any casing. Returns {@link Optional#empty()}, never {@code null}, when
 *       no user with that username exists. Implementations enforce actual uniqueness on the
 *       normalized username — this port does not.</li>
 *   <li>{@link #findById(UserId)} returns {@link Optional#empty()}, never {@code null}, when no
 *       user with that id exists.</li>
 *   <li>{@link #save(User)} upserts by {@link UserId} and returns the persisted user.</li>
 *   <li>{@link #findAll()} returns a snapshot; the returned list is not a live view of the
 *       store.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use — multiple control-plane operations (login,
 * user management) may read/write users concurrently, and no caller assumes exclusive access.
 */
public interface UserRepositoryPort {

    /**
     * Finds a user by username, matching case-insensitively.
     *
     * @param username the username to look up, in any casing
     * @return the user, or {@link Optional#empty()} if none exists
     */
    Optional<User> findByUsername(String username);

    /**
     * Finds a user by id.
     *
     * @param id the user id
     * @return the user, or {@link Optional#empty()} if none exists
     */
    Optional<User> findById(UserId id);

    /**
     * Inserts or updates a user.
     *
     * @param user the user to persist
     * @return the persisted user
     */
    User save(User user);

    /**
     * Lists all users.
     *
     * @return an immutable snapshot of all users
     */
    List<User> findAll();
}

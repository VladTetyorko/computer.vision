package com.drones.vision.application;

import com.drones.vision.domain.model.User;
import com.drones.vision.domain.model.UserId;

import java.util.List;

/**
 * Creates and manages {@link User} accounts (docs/U-AUTH-PLAN.md, wave 2).
 *
 * <p><strong>Slice 1 scope:</strong> {@link #create(UserSpec)} is deliberately unscoped — any
 * caller may create a user with any {@link com.drones.vision.domain.model.Role} in any
 * {@link com.drones.vision.domain.model.Group}, since there is no visibility model yet to check
 * against. The invite flow and the "a manager may only grant roles ≤ their own, in groups they
 * belong to" rule are slice 2 (docs/U-AUTH-PLAN.md, "Explicitly deferred to slice 2") — not built
 * here.
 */
public interface UserService {

    /**
     * Creates a new user, hashing {@link UserSpec#rawPassword()} before storage.
     *
     * @param spec the new user's shape
     * @return the created, persisted user
     * @throws IllegalStateException if {@link UserSpec#username()} is already taken
     */
    User create(UserSpec spec);

    /**
     * Lists all users.
     *
     * @return every user, in repository order
     */
    List<User> list();

    /**
     * Enables or disables a user's ability to authenticate. Idempotent.
     *
     * @param id      the user to update
     * @param enabled the new enabled state
     * @return the updated user
     * @throws java.util.NoSuchElementException if {@code id} is unknown
     */
    User setEnabled(UserId id, boolean enabled);
}

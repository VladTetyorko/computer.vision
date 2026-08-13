package com.drones.vision.identity.domain.port;

import com.drones.vision.identity.domain.model.User;

/**
 * Driven port: hash and verify raw passwords (docs/plans/done/U-AUTH-PLAN.md, wave 2).
 *
 * <p>The domain and application layers never see the hashing algorithm — BCrypt in production,
 * supplied by {@code vision-app} (wave 3) — they only see this seam. {@link #hash(String)}'s
 * return value is exactly what {@link User#passwordHash()} stores; {@link #verify(String, String)}
 * against a loaded user's {@code passwordHash()} is how authentication checks a password
 * (see {@code AuthService}, {@code vision-application}).
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #hash(String)} returns a non-blank, opaque string; the same raw password hashed
 *       twice need not (and typically will not) produce the same string — callers must never
 *       compare hashes for equality, only via {@link #verify(String, String)}.</li>
 *   <li>{@link #verify(String, String)} returns {@code true} iff {@code rawPassword} is the
 *       password that produced {@code hash}. Implementations should compare in constant time
 *       (or as close to it as the underlying algorithm allows) so a failed check does not leak
 *       timing information about how much of the hash matched.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use — logins and user creation may call this port
 * concurrently.
 */
public interface PasswordHasherPort {

    /**
     * Hashes a raw password for storage.
     *
     * @param rawPassword the plaintext password; must not be blank
     * @return an opaque hash string, suitable for {@link User#passwordHash()}
     */
    String hash(String rawPassword);

    /**
     * Verifies a raw password against a previously produced hash.
     *
     * @param rawPassword the plaintext password to check
     * @param hash        the hash to check it against, as produced by {@link #hash(String)}
     * @return {@code true} iff {@code rawPassword} matches {@code hash}
     */
    boolean verify(String rawPassword, String hash);
}

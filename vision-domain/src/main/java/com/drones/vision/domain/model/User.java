package com.drones.vision.domain.model;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The identity aggregate: a login-capable account, its group {@link Membership}s, and whether it
 * may currently authenticate.
 *
 * <p>{@code username} is normalized to lower-case in the compact constructor (trimmed, then
 * {@link String#toLowerCase(Locale)} with {@link Locale#ROOT}) so lookups and comparisons are
 * case-insensitive by construction; actual uniqueness (two users cannot share a normalized
 * username) is enforced by {@link com.drones.vision.domain.port.out.UserRepositoryPort}
 * implementations, not this record — a record has no notion of "the other rows."
 *
 * <p>{@code email} is checked only for the shape a plausible address has (non-blank, contains
 * {@code '@'}) — this is deliberately not a full RFC 5322 validator; it exists to catch obvious
 * typos, not to police what counts as a valid mailbox.
 *
 * <p><strong>{@code passwordHash} is opaque to the domain.</strong> This record stores exactly
 * the hashed string it is given and never hashes, verifies, or otherwise interprets it — hashing
 * (BCrypt in production) lives behind the application layer's {@code PasswordVerifier} seam
 * (docs/plans/done/U-AUTH-PLAN.md, wave 2). Nobody should add hashing logic here.
 *
 * <p>{@code memberships} is defensively copied via {@link List#copyOf}; it may be empty — a user
 * with no membership is a valid, if inert, state (they exist but belong to no group yet), so
 * {@link #topRole()} returns {@link Optional#empty()} rather than throwing.
 *
 * @param id           typed user identity
 * @param username     login handle; must not be blank; normalized to lower-case (trimmed)
 * @param displayName  human-readable name; must not be blank
 * @param email        contact address; must not be blank and must contain {@code '@'} (shape check only)
 * @param passwordHash the already-hashed password; must not be blank; never hashed or interpreted here
 * @param enabled      whether this account may currently authenticate
 * @param memberships  the groups this user belongs to and their role in each; defensively copied; may be empty
 */
public record User(UserId id, String username, String displayName, String email, String passwordHash,
                    boolean enabled, List<Membership> memberships) {

    public User {
        if (id == null) {
            throw new IllegalArgumentException("User id must not be null");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("User username must not be blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("User displayName must not be blank");
        }
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new IllegalArgumentException("User email must be a non-blank address containing '@'");
        }
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("User passwordHash must not be blank");
        }
        if (memberships == null) {
            throw new IllegalArgumentException("User memberships must not be null");
        }
        username = username.trim().toLowerCase(Locale.ROOT);
        memberships = List.copyOf(memberships);
    }

    /**
     * Creates a user with no group memberships yet.
     *
     * <p>There is deliberately no convenience constructor that omits {@code passwordHash}: a
     * user without one can never authenticate, so it is kept required rather than defaulted.
     */
    public User(UserId id, String username, String displayName, String email, String passwordHash,
                boolean enabled) {
        this(id, username, displayName, email, passwordHash, enabled, List.of());
    }

    /**
     * The highest-privilege {@link Role} this user holds across all their memberships.
     *
     * <p>Privilege is compared by enum ordinal ({@link Role}'s declared order is least→most
     * privileged), so this is equivalent to the maximum {@link Membership#role()} by
     * {@link Comparable natural ordering}.
     *
     * @return the highest role held, or {@link Optional#empty()} if this user has no
     *         memberships — an unassigned user is a valid state; callers decide what that means
     */
    public Optional<Role> topRole() {
        return memberships.stream()
                .map(Membership::role)
                .max(Comparator.naturalOrder());
    }

    /**
     * Renders this user for logs/debugging with {@code passwordHash} redacted.
     *
     * <p>Records auto-generate a {@code toString()} that includes every component; overridden
     * here so the password hash never leaks into logs or error messages.
     *
     * @return a string listing every field except {@code passwordHash}, which is replaced with
     *         a fixed placeholder
     */
    @Override
    public String toString() {
        return "User[id=" + id
                + ", username=" + username
                + ", displayName=" + displayName
                + ", email=" + email
                + ", passwordHash=***"
                + ", enabled=" + enabled
                + ", memberships=" + memberships
                + "]";
    }
}

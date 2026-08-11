package com.drones.vision.application.identity;

import com.drones.vision.domain.model.Membership;
import com.drones.vision.domain.model.User;

import java.util.List;
import com.drones.vision.application.asset.AssetSpec;
import com.drones.vision.application.geofence.GeofenceZoneSpec;

/**
 * Everything needed to create a {@link User} (docs/plans/done/U-AUTH-PLAN.md, wave 2) — a top-level record
 * rather than a type nested in {@link UserService}, so callers can name their input without
 * importing the service, same reasoning as {@link GeofenceZoneSpec}/{@link AssetSpec}.
 *
 * <p>Validation here is deliberately minimal: {@link User}'s own compact constructor already
 * rejects a blank {@code username}/{@code displayName}/malformed {@code email} once
 * {@link DefaultUserService#create(UserSpec)} builds the aggregate, so duplicating those checks
 * here would just be two places to keep in sync. {@code rawPassword} is the one field {@code User}
 * can never validate — it only ever sees the hash — so it is validated here, the only place that
 * ever sees the plaintext.
 *
 * @param username     desired login handle; shape-validated by {@link User} when the aggregate is
 *                      built
 * @param displayName  human-readable name; shape-validated by {@link User}
 * @param email         contact address; shape-validated by {@link User}
 * @param rawPassword  the plaintext password to hash; must not be blank
 * @param memberships  initial group memberships; defensively copied; may be empty
 * @param enabled      whether the account may authenticate immediately
 */
public record UserSpec(String username, String displayName, String email, String rawPassword,
                        List<Membership> memberships, boolean enabled) {

    public UserSpec {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("UserSpec rawPassword must not be blank");
        }
        if (memberships == null) {
            throw new IllegalArgumentException("UserSpec memberships must not be null");
        }
        memberships = List.copyOf(memberships);
    }

    /**
     * Creates a spec for an enabled user, with the given initial memberships.
     */
    public UserSpec(String username, String displayName, String email, String rawPassword,
                     List<Membership> memberships) {
        this(username, displayName, email, rawPassword, memberships, true);
    }

    /**
     * Creates a spec for an enabled user with no initial group memberships.
     */
    public UserSpec(String username, String displayName, String email, String rawPassword) {
        this(username, displayName, email, rawPassword, List.of());
    }
}

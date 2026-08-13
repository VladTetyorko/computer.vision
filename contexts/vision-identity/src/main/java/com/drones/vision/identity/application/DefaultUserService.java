package com.drones.vision.identity.application;

import com.drones.vision.identity.domain.model.Membership;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.identity.domain.model.User;
import com.drones.vision.kernel.UserId;
import com.drones.vision.identity.domain.port.PasswordHasherPort;
import com.drones.vision.identity.domain.port.UserRepositoryPort;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.VisibilityScope;

/**
 * The one implementation of {@link UserService}.
 *
 * <h2>Threading</h2>
 * Holds no mutable state — safe to call concurrently.
 */
public final class DefaultUserService implements UserService {

    private final UserRepositoryPort userRepository;
    private final PasswordHasherPort passwordHasher;

    public DefaultUserService(UserRepositoryPort userRepository, PasswordHasherPort passwordHasher) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher must not be null");
    }

    @Override
    public User create(UserSpec spec, VisibilityScope acting) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(acting, "acting must not be null");
        if (!acting.canManageOrg()) {
            throw new AccessDeniedException("not permitted to create users");
        }
        List<Membership> memberships = spec.memberships();
        if (memberships.isEmpty() && !acting.isUnbounded()) {
            throw new AccessDeniedException("cannot create a user with no group membership — "
                    + "place the user in a group you manage");
        }
        // Present whenever canManageOrg() is true (checked above), so the ceiling below is real.
        Role maxGrantable = maxGrantableRole(acting)
                .orElseThrow(() -> new AccessDeniedException("not permitted to grant any role"));
        for (Membership membership : memberships) {
            if (!acting.includesGroup(membership.groupId())) {
                throw new AccessDeniedException("cannot grant membership in a group outside your scope");
            }
            if (membership.role().compareTo(maxGrantable) > 0) {
                throw new AccessDeniedException("cannot grant a role above your own");
            }
        }
        String hash = passwordHasher.hash(spec.rawPassword());
        User candidate = new User(UserId.random(), spec.username(), spec.displayName(), spec.email(), hash,
                spec.enabled(), memberships);
        if (userRepository.findByUsername(candidate.username()).isPresent()) {
            throw new IllegalStateException("username already in use: " + candidate.username());
        }
        return userRepository.save(candidate);
    }

    @Override
    public List<User> list(VisibilityScope acting) {
        Objects.requireNonNull(acting, "acting must not be null");
        List<User> all = userRepository.findAll();
        if (acting.isUnbounded()) {
            return all;
        }
        // A GROUPS scope keeps users sharing one of its groups; an ASSIGNED_ASSETS scope's
        // includesGroup is always false, so this correctly yields an empty list for a pilot.
        return all.stream()
                .filter(user -> user.memberships().stream()
                        .anyMatch(membership -> acting.includesGroup(membership.groupId())))
                .toList();
    }

    @Override
    public User setEnabled(UserId id, boolean enabled, VisibilityScope acting) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(acting, "acting must not be null");
        if (!acting.canManageOrg()) {
            throw new AccessDeniedException("not permitted to enable/disable users");
        }
        User existing = userRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("unknown user: " + id));
        if (!acting.isUnbounded() && existing.memberships().stream()
                .noneMatch(membership -> acting.includesGroup(membership.groupId()))) {
            throw new AccessDeniedException("cannot enable/disable a user outside your scope");
        }
        User updated = new User(existing.id(), existing.username(), existing.displayName(), existing.email(),
                existing.passwordHash(), enabled, existing.memberships());
        return userRepository.save(updated);
    }

    /**
     * The highest {@link Role} {@code scope} may grant to someone else — the ≤-own-scope grant
     * ceiling {@link #create} enforces. An unbounded scope (ADMIN) may grant {@link Role#ADMIN}; a
     * groups scope (MANAGER) may grant at most {@link Role#MANAGER}; an assigned-assets scope
     * (PILOT, or no membership at all) may grant nothing — but never reaches here, since
     * {@link VisibilityScope#canManageOrg()} already gates it out first.
     *
     * <p>Granting roles is user administration, not visibility, so this lives here rather than on
     * {@link VisibilityScope} itself (docs/plans/active/DOMAIN-SEPARATION-W1.md §15, W1.6a) — its
     * only caller was always this class.
     *
     * @param scope the acting user's visibility scope
     * @return the maximum grantable role, or {@link Optional#empty()} if {@code scope} may grant none
     */
    private static Optional<Role> maxGrantableRole(VisibilityScope scope) {
        return switch (scope.kind()) {
            case UNBOUNDED -> Optional.of(Role.ADMIN);
            case GROUPS -> Optional.of(Role.MANAGER);
            case ASSIGNED_ASSETS -> Optional.empty();
        };
    }
}

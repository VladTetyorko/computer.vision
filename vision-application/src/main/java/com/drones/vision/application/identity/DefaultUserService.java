package com.drones.vision.application.identity;

import com.drones.vision.domain.model.Membership;
import com.drones.vision.domain.model.Role;
import com.drones.vision.domain.model.User;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.PasswordHasherPort;
import com.drones.vision.domain.port.out.UserRepositoryPort;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import com.drones.vision.application.scope.AccessDeniedException;
import com.drones.vision.application.scope.VisibilityScope;

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
        Role maxGrantable = acting.maxGrantableRole()
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
}

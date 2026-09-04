package com.drones.vision.identity.application;

import com.drones.vision.identity.domain.model.Membership;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.identity.domain.model.User;
import com.drones.vision.kernel.UserId;
import com.drones.vision.identity.domain.port.PasswordHasherPort;
import com.drones.vision.identity.domain.port.UserRepositoryPort;
import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.platform.AuditTrailPort;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.Authority;
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
    private final AuditTrailPort auditTrail;

    public DefaultUserService(UserRepositoryPort userRepository, PasswordHasherPort passwordHasher,
                               AuditTrailPort auditTrail) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher must not be null");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail must not be null");
    }

    @Override
    public User create(UserSpec spec, UserId actor, Authority acting) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(acting, "acting must not be null");
        if (!acting.mayManageOrg()) {
            throw new AccessDeniedException("not permitted to create users");
        }
        List<Membership> memberships = spec.memberships();
        if (memberships.isEmpty() && !acting.scope().isUnbounded()) {
            throw new AccessDeniedException("cannot create a user with no group membership — "
                    + "place the user in a group you manage");
        }
        // Present whenever mayManageOrg() is true (checked above), so the ceiling below is real.
        Role maxGrantable = maxGrantableRole(acting.scope())
                .orElseThrow(() -> new AccessDeniedException("not permitted to grant any role"));
        for (Membership membership : memberships) {
            if (!acting.scope().includesGroup(membership.groupId())) {
                throw new AccessDeniedException("cannot grant membership in a group outside your scope");
            }
            if (membership.role().compareTo(maxGrantable) > 0) {
                throw new AccessDeniedException("cannot grant a role above your own");
            }
        }
        String hash = passwordHasher.hash(spec.rawPassword());
        // mustChangePassword=true: an admin/manager always chooses this password on someone else's
        // behalf here (docs/plans/active/AUTH-ROLES-PLAN.md D13) — see UserService's own javadoc.
        User candidate = new User(UserId.random(), spec.username(), spec.displayName(), spec.email(), hash,
                spec.enabled(), true, memberships);
        if (userRepository.findByUsername(candidate.username()).isPresent()) {
            throw new IllegalStateException("username already in use: " + candidate.username());
        }
        User saved = userRepository.save(candidate);
        auditTrail.record(AuditEntry.of(actor, AuditAction.CREATED, AuditTargetType.USER,
                saved.id().value().toString(), "created user " + saved.username()));
        return saved;
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
    public User setEnabled(UserId id, boolean enabled, UserId actor, Authority acting) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(acting, "acting must not be null");
        if (!acting.mayManageOrg()) {
            throw new AccessDeniedException("not permitted to enable/disable users");
        }
        User existing = userRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("unknown user: " + id));
        if (!acting.scope().isUnbounded() && existing.memberships().stream()
                .noneMatch(membership -> acting.scope().includesGroup(membership.groupId()))) {
            throw new AccessDeniedException("cannot enable/disable a user outside your scope");
        }
        User updated = new User(existing.id(), existing.username(), existing.displayName(), existing.email(),
                existing.passwordHash(), enabled, existing.mustChangePassword(), existing.memberships());
        User saved = userRepository.save(updated);
        if (existing.enabled() != enabled) {
            auditTrail.record(AuditEntry.of(actor, enabled ? AuditAction.ACTIVATED : AuditAction.DEACTIVATED,
                    AuditTargetType.USER, saved.id().value().toString(),
                    (enabled ? "enabled user " : "disabled user ") + saved.username()));
        }
        return saved;
    }

    @Override
    public User setMemberships(UserId id, List<Membership> memberships, UserId actor, Authority acting) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(memberships, "memberships must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(acting, "acting must not be null");
        if (!acting.mayManageOrg()) {
            throw new AccessDeniedException("not permitted to edit user memberships");
        }
        User existing = userRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("unknown user: " + id));
        if (!acting.scope().isUnbounded() && existing.memberships().stream()
                .noneMatch(membership -> acting.scope().includesGroup(membership.groupId()))) {
            throw new AccessDeniedException("cannot edit a user outside your scope");
        }
        if (memberships.isEmpty() && !acting.scope().isUnbounded()) {
            throw new AccessDeniedException("cannot remove a user's last group membership — "
                    + "place the user in a group you manage");
        }
        Role maxGrantable = maxGrantableRole(acting.scope())
                .orElseThrow(() -> new AccessDeniedException("not permitted to grant any role"));
        for (Membership membership : memberships) {
            if (!acting.scope().includesGroup(membership.groupId())) {
                throw new AccessDeniedException("cannot grant membership in a group outside your scope");
            }
            if (membership.role().compareTo(maxGrantable) > 0) {
                throw new AccessDeniedException("cannot grant a role above your own");
            }
        }
        List<Membership> before = existing.memberships();
        User updated = new User(existing.id(), existing.username(), existing.displayName(), existing.email(),
                existing.passwordHash(), existing.enabled(), existing.mustChangePassword(),
                List.copyOf(memberships));
        User saved = userRepository.save(updated);
        List<AuditEntry> changes = new ArrayList<>();
        for (Membership membership : saved.memberships()) {
            if (!before.contains(membership)) {
                changes.add(AuditEntry.of(actor, AuditAction.GRANTED, AuditTargetType.GROUP,
                        membership.groupId().value().toString(),
                        "granted " + saved.username() + " the " + membership.role() + " role"));
            }
        }
        for (Membership membership : before) {
            if (!saved.memberships().contains(membership)) {
                changes.add(AuditEntry.of(actor, AuditAction.REVOKED, AuditTargetType.GROUP,
                        membership.groupId().value().toString(),
                        "revoked " + saved.username() + "'s " + membership.role() + " role"));
            }
        }
        changes.forEach(auditTrail::record);
        return saved;
    }

    @Override
    public User setPassword(UserId id, String rawPassword, UserId actor, Authority acting) {
        Objects.requireNonNull(id, "id must not be null");
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("rawPassword must not be blank");
        }
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(acting, "acting must not be null");
        if (!acting.mayManageOrg()) {
            throw new AccessDeniedException("not permitted to reset passwords");
        }
        User existing = userRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("unknown user: " + id));
        if (!acting.scope().isUnbounded() && existing.memberships().stream()
                .noneMatch(membership -> acting.scope().includesGroup(membership.groupId()))) {
            throw new AccessDeniedException("cannot reset a password outside your scope");
        }
        User updated = new User(existing.id(), existing.username(), existing.displayName(), existing.email(),
                passwordHasher.hash(rawPassword), existing.enabled(), true, existing.memberships());
        User saved = userRepository.save(updated);
        auditTrail.record(AuditEntry.of(actor, AuditAction.UPDATED, AuditTargetType.USER,
                saved.id().value().toString(), "password reset for " + saved.username()));
        return saved;
    }

    @Override
    public User createFirstAdmin(FirstAdminSpec spec) {
        Objects.requireNonNull(spec, "spec must not be null");
        if (hasEnabledAdmin()) {
            throw new IllegalStateException("an administrator already exists");
        }
        if (userRepository.findByUsername(spec.username()).isPresent()) {
            throw new IllegalStateException("username already in use: " + spec.username());
        }
        String hash = passwordHasher.hash(spec.rawPassword());
        User candidate = new User(UserId.random(), spec.username(), spec.displayName(), spec.email(), hash,
                true, false, List.of(new Membership(spec.groupId(), Role.ADMIN)));
        User saved = userRepository.save(candidate);
        auditTrail.record(AuditEntry.of(saved.id(), AuditAction.CREATED, AuditTargetType.USER,
                saved.id().value().toString(), "bootstrap: created first administrator " + saved.username()));
        return saved;
    }

    /** Same enabled-ADMIN check as {@code DefaultAuthService#adminExists()} (duplicated rather than
     *  reached through that service — this class depends only on ports, never on a sibling
     *  application service, so it does not import {@code AuthService} just for this one predicate). */
    private boolean hasEnabledAdmin() {
        return userRepository.findAll().stream()
                .anyMatch(user -> user.enabled() && user.topRole().equals(Optional.of(Role.ADMIN)));
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

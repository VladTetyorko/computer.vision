package com.drones.vision.identity.application;

import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.identity.domain.model.User;
import com.drones.vision.kernel.UserId;
import com.drones.vision.identity.domain.port.PasswordHasherPort;
import com.drones.vision.identity.domain.port.UserRepositoryPort;
import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.platform.AuditTrailPort;

import java.util.Objects;
import java.util.Optional;

/**
 * The one implementation of {@link AuthService}.
 *
 * <h2>Threading</h2>
 * Holds no mutable state — safe to call concurrently.
 */
public final class DefaultAuthService implements AuthService {

    private final UserRepositoryPort userRepository;
    private final PasswordHasherPort passwordHasher;
    private final AuditTrailPort auditTrail;

    public DefaultAuthService(UserRepositoryPort userRepository, PasswordHasherPort passwordHasher,
                               AuditTrailPort auditTrail) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher must not be null");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail must not be null");
    }

    @Override
    public Optional<User> authenticate(String username, String rawPassword) {
        if (username == null || username.isBlank() || rawPassword == null || rawPassword.isBlank()) {
            return Optional.empty();
        }
        // Only a username that resolves to a real, findable user is ever audited: AuditEntry#actor
        // is required and non-null, and there is no user id to attribute an unknown-username
        // attempt to. This does not weaken the trail for its stated purpose ("who was allowed to
        // fly that") — an attacker guessing usernames leaves no account to have been compromised.
        Optional<User> found = userRepository.findByUsername(username);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        User user = found.get();
        boolean success = user.enabled() && passwordHasher.verify(rawPassword, user.passwordHash());
        auditTrail.record(AuditEntry.of(user.id(), success ? AuditAction.LOGIN : AuditAction.LOGIN_FAILED,
                AuditTargetType.USER, user.id().value().toString(),
                (success ? "login: " : "failed login attempt: ") + user.username()));
        return success ? Optional.of(user) : Optional.empty();
    }

    @Override
    public Optional<User> find(UserId id) {
        Objects.requireNonNull(id, "id must not be null");
        return userRepository.findById(id);
    }

    @Override
    public Optional<User> loadByUsername(String username) {
        Objects.requireNonNull(username, "username must not be null");
        return userRepository.findByUsername(username);
    }

    @Override
    public Optional<User> changePassword(UserId id, String currentPassword, String newPassword) {
        Objects.requireNonNull(id, "id must not be null");
        if (currentPassword == null || currentPassword.isBlank()) {
            return Optional.empty();
        }
        if (newPassword == null || newPassword.isBlank()) {
            throw new IllegalArgumentException("newPassword must not be blank");
        }
        Optional<User> existing = userRepository.findById(id)
                .filter(User::enabled)
                .filter(user -> passwordHasher.verify(currentPassword, user.passwordHash()));
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        User user = existing.get();
        User updated = new User(user.id(), user.username(), user.displayName(), user.email(),
                passwordHasher.hash(newPassword), user.enabled(), false, user.memberships());
        User saved = userRepository.save(updated);
        auditTrail.record(AuditEntry.of(saved.id(), AuditAction.UPDATED, AuditTargetType.USER,
                saved.id().value().toString(), "password changed by " + saved.username()));
        return Optional.of(saved);
    }

    @Override
    public boolean adminExists() {
        return userRepository.findAll().stream()
                .anyMatch(user -> user.enabled() && user.topRole().equals(Optional.of(Role.ADMIN)));
    }
}

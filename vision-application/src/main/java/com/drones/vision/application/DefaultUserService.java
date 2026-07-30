package com.drones.vision.application;

import com.drones.vision.domain.model.User;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.PasswordHasherPort;
import com.drones.vision.domain.port.out.UserRepositoryPort;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

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
    public User create(UserSpec spec) {
        Objects.requireNonNull(spec, "spec must not be null");
        String hash = passwordHasher.hash(spec.rawPassword());
        User candidate = new User(UserId.random(), spec.username(), spec.displayName(), spec.email(), hash,
                spec.enabled(), spec.memberships());
        if (userRepository.findByUsername(candidate.username()).isPresent()) {
            throw new IllegalStateException("username already in use: " + candidate.username());
        }
        return userRepository.save(candidate);
    }

    @Override
    public List<User> list() {
        return userRepository.findAll();
    }

    @Override
    public User setEnabled(UserId id, boolean enabled) {
        Objects.requireNonNull(id, "id must not be null");
        User existing = userRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("unknown user: " + id));
        User updated = new User(existing.id(), existing.username(), existing.displayName(), existing.email(),
                existing.passwordHash(), enabled, existing.memberships());
        return userRepository.save(updated);
    }
}

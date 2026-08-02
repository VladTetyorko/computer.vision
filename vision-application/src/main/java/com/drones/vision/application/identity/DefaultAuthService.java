package com.drones.vision.application.identity;

import com.drones.vision.domain.model.User;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.PasswordHasherPort;
import com.drones.vision.domain.port.out.UserRepositoryPort;

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

    public DefaultAuthService(UserRepositoryPort userRepository, PasswordHasherPort passwordHasher) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher must not be null");
    }

    @Override
    public Optional<User> authenticate(String username, String rawPassword) {
        if (username == null || username.isBlank() || rawPassword == null || rawPassword.isBlank()) {
            return Optional.empty();
        }
        return userRepository.findByUsername(username)
                .filter(User::enabled)
                .filter(user -> passwordHasher.verify(rawPassword, user.passwordHash()));
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
}

package com.drones.vision.application.identity;

import com.drones.vision.identity.domain.model.User;
import com.drones.vision.kernel.UserId;
import com.drones.vision.identity.domain.port.PasswordHasherPort;
import com.drones.vision.identity.domain.port.UserRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultAuthServiceTest {

    private FakeUserRepositoryPort userRepository;
    private FakePasswordHasherPort passwordHasher;
    private AuthService service;

    @BeforeEach
    void setUp() {
        userRepository = new FakeUserRepositoryPort();
        passwordHasher = new FakePasswordHasherPort();
        service = new DefaultAuthService(userRepository, passwordHasher);
    }

    private User seedUser(String username, String rawPassword, boolean enabled) {
        User user = new User(UserId.random(), username, "Display " + username, username + "@example.com",
                passwordHasher.hash(rawPassword), enabled, List.of());
        userRepository.save(user);
        return user;
    }

    @Test
    void authenticateSucceedsForAnEnabledUserWithTheRightPassword() {
        User user = seedUser("pilot", "s3cret", true);

        Optional<User> result = service.authenticate("pilot", "s3cret");

        assertEquals(Optional.of(user), result);
    }

    @Test
    void authenticateFailsForTheWrongPassword() {
        seedUser("pilot", "s3cret", true);

        assertEquals(Optional.empty(), service.authenticate("pilot", "wrong"));
    }

    @Test
    void authenticateFailsForADisabledUserEvenWithTheRightPassword() {
        seedUser("pilot", "s3cret", false);

        assertEquals(Optional.empty(), service.authenticate("pilot", "s3cret"));
    }

    @Test
    void authenticateFailsForAnUnknownUsername() {
        assertEquals(Optional.empty(), service.authenticate("nobody", "s3cret"));
    }

    @Test
    void authenticateNeverThrowsForNullOrBlankCredentials() {
        assertEquals(Optional.empty(), service.authenticate(null, "s3cret"));
        assertEquals(Optional.empty(), service.authenticate(" ", "s3cret"));
        assertEquals(Optional.empty(), service.authenticate("pilot", null));
        assertEquals(Optional.empty(), service.authenticate("pilot", " "));
        assertEquals(Optional.empty(), service.authenticate(null, null));
    }

    @Test
    void findPassesThroughToTheRepositoryById() {
        User user = seedUser("pilot", "s3cret", true);

        assertEquals(Optional.of(user), service.find(user.id()));
        assertEquals(Optional.empty(), service.find(UserId.random()));
    }

    @Test
    void loadByUsernamePassesThroughToTheRepository() {
        User user = seedUser("pilot", "s3cret", true);

        assertEquals(Optional.of(user), service.loadByUsername("PILOT"));
        assertEquals(Optional.empty(), service.loadByUsername("nobody"));
    }

    @Test
    void constructorRejectsNullCollaborators() {
        assertThrows(NullPointerException.class, () -> new DefaultAuthService(null, passwordHasher));
        assertThrows(NullPointerException.class, () -> new DefaultAuthService(userRepository, null));
    }

    /** hash = "H:" + raw; verify checks the same relationship. Never used in production. */
    private static final class FakePasswordHasherPort implements PasswordHasherPort {
        @Override
        public String hash(String rawPassword) {
            return "H:" + rawPassword;
        }

        @Override
        public boolean verify(String rawPassword, String hash) {
            return ("H:" + rawPassword).equals(hash);
        }
    }

    /** In-memory {@link UserRepositoryPort}, keyed by id; username lookup scans case-insensitively. */
    private static final class FakeUserRepositoryPort implements UserRepositoryPort {
        private final Map<UserId, User> users = new ConcurrentHashMap<>();

        @Override
        public Optional<User> findByUsername(String username) {
            String normalized = username.toLowerCase(Locale.ROOT);
            return users.values().stream()
                    .filter(user -> user.username().equals(normalized))
                    .findFirst();
        }

        @Override
        public Optional<User> findById(UserId id) {
            return Optional.ofNullable(users.get(id));
        }

        @Override
        public User save(User user) {
            users.put(user.id(), user);
            return user;
        }

        @Override
        public List<User> findAll() {
            return List.copyOf(users.values());
        }
    }
}

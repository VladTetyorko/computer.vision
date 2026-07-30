package com.drones.vision.application;

import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.Membership;
import com.drones.vision.domain.model.Role;
import com.drones.vision.domain.model.User;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.PasswordHasherPort;
import com.drones.vision.domain.port.out.UserRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultUserServiceTest {

    private FakeUserRepositoryPort userRepository;
    private FakePasswordHasherPort passwordHasher;
    private UserService service;

    @BeforeEach
    void setUp() {
        userRepository = new FakeUserRepositoryPort();
        passwordHasher = new FakePasswordHasherPort();
        service = new DefaultUserService(userRepository, passwordHasher);
    }

    private static UserSpec spec(String username) {
        return new UserSpec(username, "Display " + username, username + "@example.com", "s3cret");
    }

    @Test
    void createHashesThePasswordRatherThanStoringItRaw() {
        User created = service.create(spec("pilot"));

        assertNotEquals("s3cret", created.passwordHash());
        assertTrue(passwordHasher.verify("s3cret", created.passwordHash()));
    }

    @Test
    void createAssignsARandomIdAndPersistsTheUser() {
        User created = service.create(spec("pilot"));

        assertEquals(Optional.of(created), userRepository.findById(created.id()));
        assertTrue(created.enabled());
    }

    @Test
    void createRejectsADuplicateUsername() {
        service.create(spec("pilot"));

        assertThrows(IllegalStateException.class, () -> service.create(spec("PILOT")));
    }

    @Test
    void createBuildsMembershipsFromTheSpec() {
        Membership membership = new Membership(GroupId.random(), Role.MANAGER);
        UserSpec spec = new UserSpec("manager", "Manager One", "manager@example.com", "s3cret", List.of(membership));

        User created = service.create(spec);

        assertEquals(List.of(membership), created.memberships());
    }

    @Test
    void listReturnsEveryUser() {
        service.create(spec("pilot"));
        service.create(spec("manager"));

        assertEquals(2, service.list().size());
    }

    @Test
    void setEnabledTogglesTheFlagAndIsIdempotent() {
        User created = service.create(new UserSpec("pilot", "Pilot", "pilot@example.com", "s3cret",
                List.of(), true));

        User disabled = service.setEnabled(created.id(), false);
        assertFalse(disabled.enabled());

        User disabledAgain = service.setEnabled(created.id(), false);
        assertFalse(disabledAgain.enabled());

        User reenabled = service.setEnabled(created.id(), true);
        assertTrue(reenabled.enabled());
    }

    @Test
    void setEnabledThrowsForAnUnknownId() {
        assertThrows(NoSuchElementException.class, () -> service.setEnabled(UserId.random(), true));
    }

    @Test
    void constructorRejectsNullCollaborators() {
        assertThrows(NullPointerException.class, () -> new DefaultUserService(null, passwordHasher));
        assertThrows(NullPointerException.class, () -> new DefaultUserService(userRepository, null));
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

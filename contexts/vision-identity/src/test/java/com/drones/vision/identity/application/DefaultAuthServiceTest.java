package com.drones.vision.identity.application;

import com.drones.vision.identity.domain.model.Membership;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.identity.domain.model.User;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.identity.domain.port.PasswordHasherPort;
import com.drones.vision.identity.domain.port.UserRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.platform.AuditTrailPort;

class DefaultAuthServiceTest {

    private FakeUserRepositoryPort userRepository;
    private FakePasswordHasherPort passwordHasher;
    private FakeAuditTrailPort auditTrail;
    private AuthService service;

    @BeforeEach
    void setUp() {
        userRepository = new FakeUserRepositoryPort();
        passwordHasher = new FakePasswordHasherPort();
        auditTrail = new FakeAuditTrailPort();
        service = new DefaultAuthService(userRepository, passwordHasher, auditTrail);
    }

    private User seedUser(String username, String rawPassword, boolean enabled) {
        User user = new User(UserId.random(), username, "Display " + username, username + "@example.com",
                passwordHasher.hash(rawPassword), enabled, false, List.of());
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
    void authenticateRecordsALoginEntryOnSuccess() {
        User user = seedUser("pilot", "s3cret", true);

        service.authenticate("pilot", "s3cret");

        assertEquals(1, auditTrail.entries.size());
        AuditEntry entry = auditTrail.entries.get(0);
        assertEquals(user.id(), entry.actor());
        assertEquals(AuditAction.LOGIN, entry.action());
        assertEquals(AuditTargetType.USER, entry.targetType());
        assertEquals(user.id().value().toString(), entry.targetId());
    }

    @Test
    void authenticateFailsForTheWrongPassword() {
        seedUser("pilot", "s3cret", true);

        assertEquals(Optional.empty(), service.authenticate("pilot", "wrong"));
    }

    @Test
    void authenticateRecordsALoginFailedEntryForAKnownUserWithTheWrongPassword() {
        User user = seedUser("pilot", "s3cret", true);

        service.authenticate("pilot", "wrong");

        assertEquals(1, auditTrail.entries.size());
        AuditEntry entry = auditTrail.entries.get(0);
        assertEquals(user.id(), entry.actor());
        assertEquals(AuditAction.LOGIN_FAILED, entry.action());
    }

    @Test
    void authenticateFailsForADisabledUserEvenWithTheRightPassword() {
        seedUser("pilot", "s3cret", false);

        assertEquals(Optional.empty(), service.authenticate("pilot", "s3cret"));
    }

    @Test
    void authenticateRecordsALoginFailedEntryForADisabledUser() {
        seedUser("pilot", "s3cret", false);

        service.authenticate("pilot", "s3cret");

        assertEquals(1, auditTrail.entries.size());
        assertEquals(AuditAction.LOGIN_FAILED, auditTrail.entries.get(0).action());
    }

    @Test
    void authenticateFailsForAnUnknownUsername() {
        assertEquals(Optional.empty(), service.authenticate("nobody", "s3cret"));
    }

    @Test
    void authenticateNeverAuditsAnUnknownUsername() {
        // No real user id exists to attribute the attempt to -- AuditEntry#actor is required.
        service.authenticate("nobody", "s3cret");

        assertTrue(auditTrail.entries.isEmpty());
    }

    @Test
    void authenticateNeverThrowsForNullOrBlankCredentials() {
        assertEquals(Optional.empty(), service.authenticate(null, "s3cret"));
        assertEquals(Optional.empty(), service.authenticate(" ", "s3cret"));
        assertEquals(Optional.empty(), service.authenticate("pilot", null));
        assertEquals(Optional.empty(), service.authenticate("pilot", " "));
        assertEquals(Optional.empty(), service.authenticate(null, null));
        assertTrue(auditTrail.entries.isEmpty());
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

    // --- changePassword (docs/plans/active/AUTH-ROLES-PLAN.md §3.5, wave B2) ---

    @Test
    void changePasswordReplacesTheHashAndClearsMustChangePassword() {
        User user = seedUser("pilot", "s3cret", true);
        User withForcedChange = new User(user.id(), user.username(), user.displayName(), user.email(),
                user.passwordHash(), true, true, user.memberships());
        userRepository.save(withForcedChange);

        Optional<User> result = service.changePassword(user.id(), "s3cret", "newSecret1");

        assertTrue(result.isPresent());
        assertFalse(result.get().mustChangePassword());
        assertTrue(passwordHasher.verify("newSecret1", result.get().passwordHash()));
    }

    @Test
    void changePasswordFailsForTheWrongCurrentPassword() {
        User user = seedUser("pilot", "s3cret", true);

        assertEquals(Optional.empty(), service.changePassword(user.id(), "wrong", "newSecret1"));
        assertTrue(passwordHasher.verify("s3cret", userRepository.findById(user.id()).orElseThrow().passwordHash()),
                "a failed change must not touch the stored hash");
    }

    @Test
    void changePasswordFailsForADisabledUser() {
        User user = seedUser("pilot", "s3cret", false);

        assertEquals(Optional.empty(), service.changePassword(user.id(), "s3cret", "newSecret1"));
    }

    @Test
    void changePasswordFailsForAnUnknownId() {
        assertEquals(Optional.empty(), service.changePassword(UserId.random(), "s3cret", "newSecret1"));
    }

    @Test
    void changePasswordRejectsABlankNewPassword() {
        User user = seedUser("pilot", "s3cret", true);

        assertThrows(IllegalArgumentException.class, () -> service.changePassword(user.id(), "s3cret", " "));
    }

    @Test
    void changePasswordRecordsAnUpdatedAuditEntryOnSuccess() {
        User user = seedUser("pilot", "s3cret", true);

        service.changePassword(user.id(), "s3cret", "newSecret1");

        assertEquals(1, auditTrail.entries.size());
        assertEquals(AuditAction.UPDATED, auditTrail.entries.get(0).action());
        assertEquals(user.id(), auditTrail.entries.get(0).actor());
    }

    @Test
    void changePasswordAuditsNothingOnFailure() {
        User user = seedUser("pilot", "s3cret", true);

        service.changePassword(user.id(), "wrong", "newSecret1");

        assertTrue(auditTrail.entries.isEmpty());
    }

    // --- adminExists (docs/plans/active/AUTH-ROLES-PLAN.md §3.5, wave B2) ---

    @Test
    void adminExistsIsFalseWithNoUsers() {
        assertFalse(service.adminExists());
    }

    @Test
    void adminExistsIsFalseWithOnlyNonAdminUsers() {
        User pilot = new User(UserId.random(), "pilot", "Pilot", "pilot@example.com", "hash", true, false,
                List.of(new Membership(GroupId.random(), Role.PILOT)));
        userRepository.save(pilot);

        assertFalse(service.adminExists());
    }

    @Test
    void adminExistsIsTrueWithAnEnabledAdmin() {
        User admin = new User(UserId.random(), "admin", "Admin", "admin@example.com", "hash", true, false,
                List.of(new Membership(GroupId.random(), Role.ADMIN)));
        userRepository.save(admin);

        assertTrue(service.adminExists());
    }

    @Test
    void adminExistsIsFalseWhenTheOnlyAdminIsDisabled() {
        User admin = new User(UserId.random(), "admin", "Admin", "admin@example.com", "hash", false, false,
                List.of(new Membership(GroupId.random(), Role.ADMIN)));
        userRepository.save(admin);

        assertFalse(service.adminExists());
    }

    @Test
    void constructorRejectsNullCollaborators() {
        assertThrows(NullPointerException.class,
                () -> new DefaultAuthService(null, passwordHasher, auditTrail));
        assertThrows(NullPointerException.class,
                () -> new DefaultAuthService(userRepository, null, auditTrail));
        assertThrows(NullPointerException.class,
                () -> new DefaultAuthService(userRepository, passwordHasher, null));
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

    /** In-memory {@link AuditTrailPort}, append-only, in call order. */
    private static final class FakeAuditTrailPort implements AuditTrailPort {
        final List<AuditEntry> entries = new ArrayList<>();

        @Override
        public AuditEntry record(AuditEntry entry) {
            entries.add(entry);
            return entry;
        }

        @Override
        public List<AuditEntry> findRecent(int limit) {
            return List.copyOf(entries);
        }

        @Override
        public List<AuditEntry> findByTarget(AuditTargetType targetType, String targetId, int limit) {
            return entries.stream()
                    .filter(e -> e.targetType() == targetType && e.targetId().equals(targetId))
                    .toList();
        }

        @Override
        public List<AuditEntry> findByActor(UserId actor, int limit) {
            return entries.stream().filter(e -> e.actor().equals(actor)).toList();
        }
    }
}

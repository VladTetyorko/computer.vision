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
import java.util.Set;
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

    private static final VisibilityScope ADMIN = VisibilityScope.unbounded();

    private static UserSpec spec(String username) {
        return new UserSpec(username, "Display " + username, username + "@example.com", "s3cret");
    }

    @Test
    void createHashesThePasswordRatherThanStoringItRaw() {
        User created = service.create(spec("pilot"), ADMIN);

        assertNotEquals("s3cret", created.passwordHash());
        assertTrue(passwordHasher.verify("s3cret", created.passwordHash()));
    }

    @Test
    void createAssignsARandomIdAndPersistsTheUser() {
        User created = service.create(spec("pilot"), ADMIN);

        assertEquals(Optional.of(created), userRepository.findById(created.id()));
        assertTrue(created.enabled());
    }

    @Test
    void createRejectsADuplicateUsername() {
        service.create(spec("pilot"), ADMIN);

        assertThrows(IllegalStateException.class, () -> service.create(spec("PILOT"), ADMIN));
    }

    @Test
    void createBuildsMembershipsFromTheSpec() {
        Membership membership = new Membership(GroupId.random(), Role.MANAGER);
        UserSpec spec = new UserSpec("manager", "Manager One", "manager@example.com", "s3cret", List.of(membership));

        User created = service.create(spec, ADMIN);

        assertEquals(List.of(membership), created.memberships());
    }

    @Test
    void listReturnsEveryUser() {
        service.create(spec("pilot"), ADMIN);
        service.create(spec("manager"), ADMIN);

        assertEquals(2, service.list(ADMIN).size());
    }

    @Test
    void setEnabledTogglesTheFlagAndIsIdempotent() {
        User created = service.create(new UserSpec("pilot", "Pilot", "pilot@example.com", "s3cret",
                List.of(), true), ADMIN);

        User disabled = service.setEnabled(created.id(), false, ADMIN);
        assertFalse(disabled.enabled());

        User disabledAgain = service.setEnabled(created.id(), false, ADMIN);
        assertFalse(disabledAgain.enabled());

        User reenabled = service.setEnabled(created.id(), true, ADMIN);
        assertTrue(reenabled.enabled());
    }

    @Test
    void setEnabledThrowsForAnUnknownId() {
        assertThrows(NoSuchElementException.class, () -> service.setEnabled(UserId.random(), true, ADMIN));
    }

    // --- management gates (docs/U-SCOPE-PLAN.md, U-e slice 2 cleanup) ---

    private UserSpec inGroup(String username, GroupId group, Role role) {
        return new UserSpec(username, "Display " + username, username + "@example.com", "s3cret",
                List.of(new Membership(group, role)));
    }

    @Test
    void managerCreatesUserInTheirGroupWithRoleUpToManager() {
        GroupId managed = GroupId.random();
        VisibilityScope manager = VisibilityScope.groups(Set.of(managed));

        User created = service.create(inGroup("newpilot", managed, Role.PILOT), manager);
        assertEquals(Role.PILOT, created.topRole().orElseThrow());

        User asManager = service.create(inGroup("newmgr", managed, Role.MANAGER), manager);
        assertEquals(Role.MANAGER, asManager.topRole().orElseThrow());
    }

    @Test
    void managerCannotCreateUserInAGroupOutsideTheirScope() {
        VisibilityScope manager = VisibilityScope.groups(Set.of(GroupId.random()));

        assertThrows(AccessDeniedException.class,
                () -> service.create(inGroup("x", GroupId.random(), Role.PILOT), manager));
    }

    @Test
    void managerCannotGrantAdmin() {
        GroupId managed = GroupId.random();
        VisibilityScope manager = VisibilityScope.groups(Set.of(managed));

        assertThrows(AccessDeniedException.class,
                () -> service.create(inGroup("x", managed, Role.ADMIN), manager));
    }

    @Test
    void managerCannotCreateAUserWithNoMemberships() {
        VisibilityScope manager = VisibilityScope.groups(Set.of(GroupId.random()));

        assertThrows(AccessDeniedException.class, () -> service.create(spec("unscoped"), manager));
    }

    @Test
    void adminMayCreateAUserWithNoMemberships() {
        User created = service.create(spec("floating"), ADMIN);
        assertTrue(created.memberships().isEmpty());
    }

    @Test
    void pilotScopeCannotCreateAtAll() {
        VisibilityScope pilot = VisibilityScope.assignedAssets(Set.of());

        assertThrows(AccessDeniedException.class,
                () -> service.create(inGroup("x", GroupId.random(), Role.PILOT), pilot));
    }

    @Test
    void managerCanSetEnabledOnlyWithinTheirSubtree() {
        GroupId managed = GroupId.random();
        VisibilityScope manager = VisibilityScope.groups(Set.of(managed));

        User inSubtree = service.create(inGroup("inside", managed, Role.PILOT), ADMIN);
        User outside = service.create(inGroup("outside", GroupId.random(), Role.PILOT), ADMIN);

        assertFalse(service.setEnabled(inSubtree.id(), false, manager).enabled());
        assertThrows(AccessDeniedException.class, () -> service.setEnabled(outside.id(), false, manager));
    }

    @Test
    void pilotScopeCannotSetEnabled() {
        User existing = service.create(inGroup("someone", GroupId.random(), Role.PILOT), ADMIN);
        VisibilityScope pilot = VisibilityScope.assignedAssets(Set.of());

        assertThrows(AccessDeniedException.class, () -> service.setEnabled(existing.id(), false, pilot));
    }

    @Test
    void listFiltersToTheManagersSubtreeAndIsEmptyForAPilot() {
        GroupId managed = GroupId.random();
        service.create(inGroup("mine", managed, Role.PILOT), ADMIN);
        service.create(inGroup("theirs", GroupId.random(), Role.PILOT), ADMIN);

        VisibilityScope manager = VisibilityScope.groups(Set.of(managed));
        List<User> visible = service.list(manager);
        assertEquals(1, visible.size());
        assertEquals("mine", visible.get(0).username());

        assertEquals(2, service.list(ADMIN).size());
        assertTrue(service.list(VisibilityScope.assignedAssets(Set.of())).isEmpty());
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

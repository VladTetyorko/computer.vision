package com.drones.vision.identity.application;

import com.drones.vision.kernel.GroupId;
import com.drones.vision.identity.domain.model.Membership;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.identity.domain.model.User;
import com.drones.vision.kernel.UserId;
import com.drones.vision.identity.domain.port.PasswordHasherPort;
import com.drones.vision.identity.domain.port.UserRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.platform.VisibilityScope;

class DefaultUserServiceTest {

    private FakeUserRepositoryPort userRepository;
    private FakePasswordHasherPort passwordHasher;
    private FakeAuditTrailPort auditTrail;
    private UserService service;

    private static final VisibilityScope ADMIN = VisibilityScope.unbounded();
    private final UserId actor = UserId.random();

    @BeforeEach
    void setUp() {
        userRepository = new FakeUserRepositoryPort();
        passwordHasher = new FakePasswordHasherPort();
        auditTrail = new FakeAuditTrailPort();
        service = new DefaultUserService(userRepository, passwordHasher, auditTrail);
    }

    private static UserSpec spec(String username) {
        return new UserSpec(username, "Display " + username, username + "@example.com", "s3cret");
    }

    @Test
    void createHashesThePasswordRatherThanStoringItRaw() {
        User created = service.create(spec("pilot"), actor, ADMIN);

        assertNotEquals("s3cret", created.passwordHash());
        assertTrue(passwordHasher.verify("s3cret", created.passwordHash()));
    }

    @Test
    void createAssignsARandomIdAndPersistsTheUser() {
        User created = service.create(spec("pilot"), actor, ADMIN);

        assertEquals(Optional.of(created), userRepository.findById(created.id()));
        assertTrue(created.enabled());
    }

    @Test
    void createAlwaysRequiresAForcedPasswordChange() {
        // D13: an admin/manager always chooses this password on someone else's behalf.
        User created = service.create(spec("pilot"), actor, ADMIN);

        assertTrue(created.mustChangePassword());
    }

    @Test
    void createRecordsACreatedAuditEntryAttributedToTheActor() {
        User created = service.create(spec("pilot"), actor, ADMIN);

        assertEquals(1, auditTrail.entries.size());
        AuditEntry entry = auditTrail.entries.get(0);
        assertEquals(actor, entry.actor());
        assertEquals(AuditAction.CREATED, entry.action());
        assertEquals(AuditTargetType.USER, entry.targetType());
        assertEquals(created.id().value().toString(), entry.targetId());
    }

    @Test
    void createRejectsADuplicateUsername() {
        service.create(spec("pilot"), actor, ADMIN);

        assertThrows(IllegalStateException.class, () -> service.create(spec("PILOT"), actor, ADMIN));
    }

    @Test
    void createBuildsMembershipsFromTheSpec() {
        Membership membership = new Membership(GroupId.random(), Role.MANAGER);
        UserSpec spec = new UserSpec("manager", "Manager One", "manager@example.com", "s3cret", List.of(membership));

        User created = service.create(spec, actor, ADMIN);

        assertEquals(List.of(membership), created.memberships());
    }

    @Test
    void listReturnsEveryUser() {
        service.create(spec("pilot"), actor, ADMIN);
        service.create(spec("manager"), actor, ADMIN);

        assertEquals(2, service.list(ADMIN).size());
    }

    @Test
    void setEnabledTogglesTheFlagAndIsIdempotent() {
        User created = service.create(new UserSpec("pilot", "Pilot", "pilot@example.com", "s3cret",
                List.of(), true), actor, ADMIN);

        User disabled = service.setEnabled(created.id(), false, actor, ADMIN);
        assertFalse(disabled.enabled());

        User disabledAgain = service.setEnabled(created.id(), false, actor, ADMIN);
        assertFalse(disabledAgain.enabled());

        User reenabled = service.setEnabled(created.id(), true, actor, ADMIN);
        assertTrue(reenabled.enabled());
    }

    @Test
    void setEnabledAuditsOnlyWhenTheStateActuallyChanges() {
        User created = service.create(spec("pilot"), actor, ADMIN);
        auditTrail.entries.clear();

        service.setEnabled(created.id(), false, actor, ADMIN);
        assertEquals(1, auditTrail.entries.size());
        assertEquals(AuditAction.DEACTIVATED, auditTrail.entries.get(0).action());

        service.setEnabled(created.id(), false, actor, ADMIN);
        assertEquals(1, auditTrail.entries.size(), "an idempotent no-op disable must not audit again");

        service.setEnabled(created.id(), true, actor, ADMIN);
        assertEquals(2, auditTrail.entries.size());
        assertEquals(AuditAction.ACTIVATED, auditTrail.entries.get(1).action());
    }

    @Test
    void setEnabledPreservesMustChangePassword() {
        User created = service.create(spec("pilot"), actor, ADMIN);
        assertTrue(created.mustChangePassword());

        User disabled = service.setEnabled(created.id(), false, actor, ADMIN);

        assertTrue(disabled.mustChangePassword());
    }

    @Test
    void setEnabledThrowsForAnUnknownId() {
        assertThrows(NoSuchElementException.class, () -> service.setEnabled(UserId.random(), true, actor, ADMIN));
    }

    // --- management gates (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2 cleanup) ---

    private UserSpec inGroup(String username, GroupId group, Role role) {
        return new UserSpec(username, "Display " + username, username + "@example.com", "s3cret",
                List.of(new Membership(group, role)));
    }

    @Test
    void managerCreatesUserInTheirGroupWithRoleUpToManager() {
        GroupId managed = GroupId.random();
        VisibilityScope manager = VisibilityScope.groups(Set.of(managed));

        User created = service.create(inGroup("newpilot", managed, Role.PILOT), actor, manager);
        assertEquals(Role.PILOT, created.topRole().orElseThrow());

        User asManager = service.create(inGroup("newmgr", managed, Role.MANAGER), actor, manager);
        assertEquals(Role.MANAGER, asManager.topRole().orElseThrow());
    }

    @Test
    void managerCannotCreateUserInAGroupOutsideTheirScope() {
        VisibilityScope manager = VisibilityScope.groups(Set.of(GroupId.random()));

        assertThrows(AccessDeniedException.class,
                () -> service.create(inGroup("x", GroupId.random(), Role.PILOT), actor, manager));
    }

    @Test
    void managerCannotGrantAdmin() {
        GroupId managed = GroupId.random();
        VisibilityScope manager = VisibilityScope.groups(Set.of(managed));

        assertThrows(AccessDeniedException.class,
                () -> service.create(inGroup("x", managed, Role.ADMIN), actor, manager));
    }

    @Test
    void adminMayGrantAdmin() {
        // The grant ceiling for an unbounded (ADMIN) scope is ADMIN itself — the one case
        // managerCannotGrantAdmin's counterpart above doesn't reach, since a manager's own ceiling
        // is MANAGER.
        User created = service.create(inGroup("newadmin", GroupId.random(), Role.ADMIN), actor, ADMIN);
        assertEquals(Role.ADMIN, created.topRole().orElseThrow());
    }

    @Test
    void managerCannotCreateAUserWithNoMemberships() {
        VisibilityScope manager = VisibilityScope.groups(Set.of(GroupId.random()));

        assertThrows(AccessDeniedException.class, () -> service.create(spec("unscoped"), actor, manager));
    }

    @Test
    void adminMayCreateAUserWithNoMemberships() {
        User created = service.create(spec("floating"), actor, ADMIN);
        assertTrue(created.memberships().isEmpty());
    }

    @Test
    void pilotScopeCannotCreateAtAll() {
        VisibilityScope pilot = VisibilityScope.assignedAssets(Set.of());

        assertThrows(AccessDeniedException.class,
                () -> service.create(inGroup("x", GroupId.random(), Role.PILOT), actor, pilot));
    }

    @Test
    void managerCanSetEnabledOnlyWithinTheirSubtree() {
        GroupId managed = GroupId.random();
        VisibilityScope manager = VisibilityScope.groups(Set.of(managed));

        User inSubtree = service.create(inGroup("inside", managed, Role.PILOT), actor, ADMIN);
        User outside = service.create(inGroup("outside", GroupId.random(), Role.PILOT), actor, ADMIN);

        assertFalse(service.setEnabled(inSubtree.id(), false, actor, manager).enabled());
        assertThrows(AccessDeniedException.class, () -> service.setEnabled(outside.id(), false, actor, manager));
    }

    @Test
    void pilotScopeCannotSetEnabled() {
        User existing = service.create(inGroup("someone", GroupId.random(), Role.PILOT), actor, ADMIN);
        VisibilityScope pilot = VisibilityScope.assignedAssets(Set.of());

        assertThrows(AccessDeniedException.class, () -> service.setEnabled(existing.id(), false, actor, pilot));
    }

    @Test
    void listFiltersToTheManagersSubtreeAndIsEmptyForAPilot() {
        GroupId managed = GroupId.random();
        service.create(inGroup("mine", managed, Role.PILOT), actor, ADMIN);
        service.create(inGroup("theirs", GroupId.random(), Role.PILOT), actor, ADMIN);

        VisibilityScope manager = VisibilityScope.groups(Set.of(managed));
        List<User> visible = service.list(manager);
        assertEquals(1, visible.size());
        assertEquals("mine", visible.get(0).username());

        assertEquals(2, service.list(ADMIN).size());
        assertTrue(service.list(VisibilityScope.assignedAssets(Set.of())).isEmpty());
    }

    // --- setMemberships (docs/plans/active/AUTH-ROLES-PLAN.md D14, wave B2) ---

    @Test
    void setMembershipsReplacesTheWholeSet() {
        User created = service.create(spec("pilot"), actor, ADMIN);
        Membership newMembership = new Membership(GroupId.random(), Role.MANAGER);

        User updated = service.setMemberships(created.id(), List.of(newMembership), actor, ADMIN);

        assertEquals(List.of(newMembership), updated.memberships());
    }

    @Test
    void setMembershipsRecordsGrantedAndRevokedPerChangedMembership() {
        GroupId keptGroup = GroupId.random();
        GroupId removedGroup = GroupId.random();
        GroupId addedGroup = GroupId.random();
        Membership kept = new Membership(keptGroup, Role.PILOT);
        Membership removed = new Membership(removedGroup, Role.PILOT);
        UserSpec spec = new UserSpec("pilot", "Pilot", "pilot@example.com", "s3cret", List.of(kept, removed));
        User created = service.create(spec, actor, ADMIN);
        auditTrail.entries.clear();

        Membership added = new Membership(addedGroup, Role.MANAGER);
        service.setMemberships(created.id(), List.of(kept, added), actor, ADMIN);

        assertEquals(2, auditTrail.entries.size());
        assertTrue(auditTrail.entries.stream().anyMatch(e -> e.action() == AuditAction.GRANTED
                && e.targetType() == AuditTargetType.GROUP && e.targetId().equals(addedGroup.value().toString())));
        assertTrue(auditTrail.entries.stream().anyMatch(e -> e.action() == AuditAction.REVOKED
                && e.targetType() == AuditTargetType.GROUP && e.targetId().equals(removedGroup.value().toString())));
    }

    @Test
    void setMembershipsWithNoActualChangeAuditsNothing() {
        Membership membership = new Membership(GroupId.random(), Role.PILOT);
        UserSpec spec = new UserSpec("pilot", "Pilot", "pilot@example.com", "s3cret", List.of(membership));
        User created = service.create(spec, actor, ADMIN);
        auditTrail.entries.clear();

        service.setMemberships(created.id(), List.of(membership), actor, ADMIN);

        assertTrue(auditTrail.entries.isEmpty());
    }

    @Test
    void setMembershipsRejectsARoleAboveTheGrantCeiling() {
        GroupId managed = GroupId.random();
        VisibilityScope manager = VisibilityScope.groups(Set.of(managed));
        User created = service.create(inGroup("pilot", managed, Role.PILOT), actor, ADMIN);

        assertThrows(AccessDeniedException.class, () -> service.setMemberships(created.id(),
                List.of(new Membership(managed, Role.ADMIN)), actor, manager));
    }

    @Test
    void setMembershipsRejectsAGroupOutsideTheActingScope() {
        GroupId managed = GroupId.random();
        VisibilityScope manager = VisibilityScope.groups(Set.of(managed));
        User created = service.create(inGroup("pilot", managed, Role.PILOT), actor, ADMIN);

        assertThrows(AccessDeniedException.class, () -> service.setMemberships(created.id(),
                List.of(new Membership(GroupId.random(), Role.PILOT)), actor, manager));
    }

    @Test
    void setMembershipsRejectsEmptyForANonUnboundedScope() {
        GroupId managed = GroupId.random();
        VisibilityScope manager = VisibilityScope.groups(Set.of(managed));
        User created = service.create(inGroup("pilot", managed, Role.PILOT), actor, ADMIN);

        assertThrows(AccessDeniedException.class,
                () -> service.setMemberships(created.id(), List.of(), actor, manager));
    }

    @Test
    void setMembershipsThrowsForAnUnknownId() {
        assertThrows(NoSuchElementException.class,
                () -> service.setMemberships(UserId.random(), List.of(), actor, ADMIN));
    }

    // --- setPassword (docs/plans/active/AUTH-ROLES-PLAN.md D13, wave B2) ---

    @Test
    void setPasswordReplacesTheHashAndForcesAChange() {
        User created = service.create(spec("pilot"), actor, ADMIN);
        String originalHash = created.passwordHash();

        User updated = service.setPassword(created.id(), "newSecret1", actor, ADMIN);

        assertNotEquals(originalHash, updated.passwordHash());
        assertTrue(passwordHasher.verify("newSecret1", updated.passwordHash()));
        assertTrue(updated.mustChangePassword());
    }

    @Test
    void setPasswordAlwaysAuditsEvenIfCalledRepeatedly() {
        User created = service.create(spec("pilot"), actor, ADMIN);
        auditTrail.entries.clear();

        service.setPassword(created.id(), "newSecret1", actor, ADMIN);
        service.setPassword(created.id(), "newSecret1", actor, ADMIN);

        assertEquals(2, auditTrail.entries.size());
        assertTrue(auditTrail.entries.stream().allMatch(e -> e.action() == AuditAction.UPDATED
                && e.targetType() == AuditTargetType.USER));
    }

    @Test
    void setPasswordRejectsABlankPassword() {
        User created = service.create(spec("pilot"), actor, ADMIN);

        assertThrows(IllegalArgumentException.class, () -> service.setPassword(created.id(), " ", actor, ADMIN));
    }

    @Test
    void setPasswordRespectsTheSameManagementGateAsSetEnabled() {
        User outside = service.create(inGroup("outside", GroupId.random(), Role.PILOT), actor, ADMIN);
        VisibilityScope manager = VisibilityScope.groups(Set.of(GroupId.random()));

        assertThrows(AccessDeniedException.class,
                () -> service.setPassword(outside.id(), "newSecret1", actor, manager));
    }

    @Test
    void setPasswordThrowsForAnUnknownId() {
        assertThrows(NoSuchElementException.class,
                () -> service.setPassword(UserId.random(), "newSecret1", actor, ADMIN));
    }

    // --- createFirstAdmin (docs/plans/active/AUTH-ROLES-PLAN.md §3.5, wave B2) ---

    @Test
    void createFirstAdminGrantsExactlyOneAdminMembership() {
        GroupId root = GroupId.random();

        User admin = service.createFirstAdmin(
                new FirstAdminSpec("root", "Root Admin", "root@example.com", "s3cret", root));

        assertEquals(List.of(new Membership(root, Role.ADMIN)), admin.memberships());
        assertTrue(admin.enabled());
    }

    @Test
    void createFirstAdminNeverForcesAPasswordChange() {
        // The operator chose this password themselves, at setup -- nobody handed it to them.
        User admin = service.createFirstAdmin(
                new FirstAdminSpec("root", "Root Admin", "root@example.com", "s3cret", GroupId.random()));

        assertFalse(admin.mustChangePassword());
    }

    @Test
    void createFirstAdminRecordsACreatedAuditEntryAttributedToItself() {
        User admin = service.createFirstAdmin(
                new FirstAdminSpec("root", "Root Admin", "root@example.com", "s3cret", GroupId.random()));

        assertEquals(1, auditTrail.entries.size());
        assertEquals(admin.id(), auditTrail.entries.get(0).actor());
        assertEquals(AuditAction.CREATED, auditTrail.entries.get(0).action());
    }

    @Test
    void createFirstAdminRefusesOnceAnAdminAlreadyExists() {
        service.createFirstAdmin(new FirstAdminSpec("root", "Root Admin", "root@example.com", "s3cret", GroupId.random()));

        assertThrows(IllegalStateException.class, () -> service.createFirstAdmin(
                new FirstAdminSpec("second", "Second Admin", "second@example.com", "s3cret", GroupId.random())));
    }

    @Test
    void createFirstAdminIsUnblockedByADisabledAdmin() {
        // A disabled admin does not count -- the latch tracks whether the station is actually
        // governable right now, not whether an admin row has ever existed.
        User admin = service.createFirstAdmin(
                new FirstAdminSpec("root", "Root Admin", "root@example.com", "s3cret", GroupId.random()));
        service.setEnabled(admin.id(), false, actor, ADMIN);

        User second = service.createFirstAdmin(
                new FirstAdminSpec("second", "Second Admin", "second@example.com", "s3cret", GroupId.random()));
        assertTrue(second.enabled());
    }

    @Test
    void createFirstAdminRejectsADuplicateUsername() {
        service.create(spec("root"), actor, ADMIN);

        assertThrows(IllegalStateException.class, () -> service.createFirstAdmin(
                new FirstAdminSpec("root", "Root Admin", "root@example.com", "s3cret", GroupId.random())));
    }

    @Test
    void constructorRejectsNullCollaborators() {
        assertThrows(NullPointerException.class,
                () -> new DefaultUserService(null, passwordHasher, auditTrail));
        assertThrows(NullPointerException.class,
                () -> new DefaultUserService(userRepository, null, auditTrail));
        assertThrows(NullPointerException.class,
                () -> new DefaultUserService(userRepository, passwordHasher, null));
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

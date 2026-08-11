package com.drones.vision.app.bootstrap;

import com.drones.vision.app.devsupport.InMemoryGroupRepository;
import com.drones.vision.app.devsupport.InMemoryUserRepository;
import com.drones.vision.application.identity.DefaultGroupService;
import com.drones.vision.application.identity.DefaultUserService;
import com.drones.vision.application.identity.GroupService;
import com.drones.vision.application.identity.UserService;
import com.drones.vision.application.scope.VisibilityScope;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.identity.domain.model.User;
import com.drones.vision.identity.domain.port.PasswordHasherPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link AuthSeedRunner}: seeds exactly once and is idempotent (docs/plans/done/U-AUTH-PLAN.md, wave 3). */
class AuthSeedRunnerTest {

    private final InMemoryUserRepository userRepository = new InMemoryUserRepository();
    private final InMemoryGroupRepository groupRepository = new InMemoryGroupRepository();
    private final PasswordHasherPort hasher = new FakeHasher();
    private final UserService userService = new DefaultUserService(userRepository, hasher);
    private final GroupService groupService = new DefaultGroupService(groupRepository);
    private final AuthSeedRunner runner = new AuthSeedRunner(userService, groupService);

    private static final VisibilityScope ADMIN = VisibilityScope.unbounded();

    @Test
    void seedsRootGroupAndThreeRoleUsersOnFirstRun() {
        runner.run(null);

        assertEquals(1, groupService.list(ADMIN).size());
        List<User> users = userService.list(ADMIN);
        assertEquals(3, users.size());
        assertEquals(Optional.of(Role.ADMIN), userByName("admin").topRole());
        assertEquals(Optional.of(Role.MANAGER), userByName("manager").topRole());
        assertEquals(Optional.of(Role.PILOT), userByName("pilot").topRole());
        // each seeded user is a member of the one seeded group
        assertTrue(users.stream().allMatch(u -> u.memberships().size() == 1));
    }

    @Test
    void isANoOpWhenUsersAlreadyExist() {
        runner.run(null);
        runner.run(null);

        assertEquals(3, userService.list(ADMIN).size());
        assertEquals(1, groupService.list(ADMIN).size());
    }

    private User userByName(String username) {
        return userRepository.findByUsername(username).orElseThrow();
    }

    /** Trivial deterministic hasher — this test is about seeding, not BCrypt. */
    private static final class FakeHasher implements PasswordHasherPort {
        @Override
        public String hash(String rawPassword) {
            return "hashed:" + rawPassword;
        }

        @Override
        public boolean verify(String rawPassword, String hash) {
            return hash.equals("hashed:" + rawPassword);
        }
    }
}

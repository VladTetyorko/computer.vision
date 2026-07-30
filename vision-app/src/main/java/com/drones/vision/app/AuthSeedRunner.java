package com.drones.vision.app;

import com.drones.vision.application.GroupService;
import com.drones.vision.application.GroupSpec;
import com.drones.vision.application.UserService;
import com.drones.vision.application.UserSpec;
import com.drones.vision.domain.model.Group;
import com.drones.vision.domain.model.Membership;
import com.drones.vision.domain.model.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.util.List;
import java.util.Objects;

/**
 * Seeds a root {@link Group} and three starter users on first boot, so flipping {@code
 * vision.auth.enabled=true} has accounts to log in as (docs/U-AUTH-PLAN.md, wave 3).
 *
 * <p><strong>These are dev-only seed credentials.</strong> {@code admin}/{@code admin} (ADMIN),
 * {@code manager}/{@code manager} (MANAGER), {@code pilot}/{@code pilot} (PILOT) — matching
 * username and password, for local development and demos only. A real deployment must create users
 * through {@link UserService} (BCrypt-hashed via the {@code PasswordHasherPort}) and never rely on
 * these. Passwords are hashed here too — they go through {@code UserService#create} — but the
 * <em>values</em> are public knowledge, so treat any environment still carrying them as unsecured.
 *
 * <p>Runs regardless of {@code vision.auth.enabled} (so the accounts exist the moment auth is
 * turned on) but is a strict no-op when any user already exists — idempotent across restarts and
 * safe with a persistent store that already holds real users. Emptiness is judged by {@link
 * UserService#list()}, so a persistence-backed run that already seeded once simply skips.
 */
final class AuthSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthSeedRunner.class);

    private final UserService userService;
    private final GroupService groupService;

    AuthSeedRunner(UserService userService, GroupService groupService) {
        this.userService = Objects.requireNonNull(userService, "userService must not be null");
        this.groupService = Objects.requireNonNull(groupService, "groupService must not be null");
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!userService.list().isEmpty()) {
            return;
        }
        Group root = groupService.create(new GroupSpec("Root", null));
        userService.create(new UserSpec("admin", "Administrator", "admin@vision.local", "admin",
                List.of(new Membership(root.id(), Role.ADMIN))));
        userService.create(new UserSpec("manager", "Manager", "manager@vision.local", "manager",
                List.of(new Membership(root.id(), Role.MANAGER))));
        userService.create(new UserSpec("pilot", "Pilot", "pilot@vision.local", "pilot",
                List.of(new Membership(root.id(), Role.PILOT))));
        log.warn("Seeded dev users admin/manager/pilot with DEV-ONLY passwords equal to their usernames "
                + "in group '{}' — change or remove before any real deployment.", root.name());
    }
}

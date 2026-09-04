package com.drones.vision.app;

import com.drones.vision.identity.application.GroupService;
import com.drones.vision.identity.application.GroupSpec;
import com.drones.vision.identity.application.UserService;
import com.drones.vision.identity.application.UserSpec;
import com.drones.vision.identity.domain.model.Group;
import com.drones.vision.identity.domain.model.Membership;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.VisibilityScope;

import java.util.List;

/**
 * Test-only stand-in for the deleted {@code AuthSeedRunner} (docs/plans/done/POSTGRES-ONLY-CONTEXT.md W1).
 *
 * <p>Dev-account seeding moved out of Java entirely: it is now a Flyway migration
 * ({@code storage/persistence}'s {@code db/seed/dev}, {@code V90001__dev_accounts.sql}) that only
 * ever runs when {@code vision.persistence.seed-dev-users} is true — there is no in-memory
 * equivalent any more, and (docs/plans/done/POSTGRES-ONLY-CONTEXT.md W2b) no other flag to pair it
 * with either.
 * The {@code *AuthEnabledTest} classes in this package run with {@code vision.auth.enabled=true}
 * but leave {@code seed-dev-users} at its default {@code false} (that flag stays reserved for
 * {@code docker-compose.yml}'s friends-demo stack, per {@code VisionPersistenceProperties}'
 * javadoc), so they need {@code admin}/{@code manager}/{@code pilot} accounts to exist some other
 * way. This recreates exactly {@code AuthSeedRunner}'s old seed (same usernames/passwords/roles)
 * directly against the application services, guarded the same way it was: a no-op once any user
 * exists.
 *
 * <p><strong>Joins the existing root group; only creates one if none exists.</strong> Since
 * docs/plans/done/POSTGRES-ONLY-CONTEXT.md W4, these tests run against a real, Flyway-migrated
 * database whose {@code V13__identity_baseline.sql} has <em>already</em> seeded a "Root" group at a
 * fixed id before this method ever runs — exactly the row {@code V90001__dev_accounts.sql} joins in
 * production. Always calling {@link GroupService#create} here (this class's original shape, written
 * before W4 gave this suite a real database) would mint a <em>second</em>, differently-id'd "Root"
 * group every time: harmless against an empty database (nothing else pre-existed to collide with),
 * but a real bug once the migrated V13 row is also in the table — {@link
 * ScopedAssetReadAuthEnabledTest#groupOf} and any other by-name lookup could then resolve the
 * <em>other</em> "Root" group, the one these seeded users are not actually members of, and every
 * group-scoped assertion would see an empty fleet. Looking the group up first and only creating one
 * on a genuine miss avoids the duplicate.
 *
 * <p><strong>Must be idempotent across calls</strong> — {@code @SpringBootTest} contexts are cached
 * and shared across every test class with identical {@code properties} (see {@link
 * ScopedAssetReadAuthEnabledTest}'s own javadoc), and all four {@code *AuthEnabledTest} classes use
 * the exact same {@code @SpringBootTest(properties = {"vision.auth.enabled=true",
 * "vision.publish.enabled=false"})}, so every one of their {@code @BeforeEach} methods calls
 * {@link #seedIfAbsent} against what may already be a fully-seeded context.
 */
final class DevAccountSeeder {

    private DevAccountSeeder() {
    }

    static void seedIfAbsent(UserService userService, GroupService groupService) {
        VisibilityScope system = VisibilityScope.unbounded();
        if (!userService.list(system).isEmpty()) {
            return;
        }
        Group root = groupService.list(system).stream()
                .filter(group -> group.name().equals("Root"))
                .findFirst()
                .orElseGet(() -> groupService.create(new GroupSpec("Root", null), system));
        // No real caller exists yet — this recreates AuthSeedRunner's old unconditional seed, so the
        // audit attribution is a fresh, unrelated system actor rather than any of the seeded accounts.
        UserId seedActor = UserId.random();
        userService.create(new UserSpec("admin", "Administrator", "admin@vision.local", "admin",
                List.of(new Membership(root.id(), Role.ADMIN))), seedActor, system);
        userService.create(new UserSpec("manager", "Manager", "manager@vision.local", "manager",
                List.of(new Membership(root.id(), Role.MANAGER))), seedActor, system);
        userService.create(new UserSpec("pilot", "Pilot", "pilot@vision.local", "pilot",
                List.of(new Membership(root.id(), Role.PILOT))), seedActor, system);
    }
}

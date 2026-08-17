package com.drones.vision.adapter.persistence;

import com.drones.vision.adapter.persistence.config.PersistenceUnit;
import com.drones.vision.adapter.persistence.repository.JpaAssignmentRepository;
import com.drones.vision.adapter.persistence.repository.JpaGroupRepository;
import com.drones.vision.adapter.persistence.repository.JpaUserRepository;
import com.drones.vision.identity.application.scope.DefaultScopeResolver;
import com.drones.vision.identity.domain.model.Group;
import com.drones.vision.identity.domain.model.User;
import com.drones.vision.identity.domain.port.GroupRepositoryPort;
import com.drones.vision.identity.domain.port.UserRepositoryPort;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.VisibilityScope;

import jakarta.persistence.EntityManagerFactory;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the W1 <strong>upgrade path</strong> (docs/plans/active/POSTGRES-ONLY-CONTEXT.md, defects 1
 * and 2) against a real Postgres seeded to look exactly like a database that already ran the
 * now-deleted {@code AuthSeedRunner}: a "Root" group at a random id, and admin/manager/pilot at
 * random ids whose memberships point at that random root — the shape every pre-W1
 * {@code docker-compose.yml} deployment actually has ({@code VISION_PERSISTENCE_ENABLED=true} +
 * {@code VISION_AUTH_ENABLED=true} against a persistent {@code postgres-data} volume, so
 * {@code AuthSeedRunner} ran on every such deployment's very first boot).
 *
 * <p>Sibling to {@link DevAccountSeedMigrationTest} rather than an addition to it: that class proves
 * the flag-gated seed contract on a database that starts empty, one Flyway {@code migrate()} call
 * per scenario. This class's scenarios need a materially different setup — migrating only up to
 * {@code V12} with a real {@link Flyway} handle, then hand-inserting rows via plain JDBC to fake a
 * pre-{@code V13} database, before finally running the full {@link PersistenceUnit#start} a real
 * caller would use — worth keeping out of that class's already-large scenario list.
 *
 * <p>Each test gets its own {@link PostgreSQLContainer} (an instance field, not {@code static} —
 * same reasoning as {@link DevAccountSeedMigrationTest}): every scenario needs to observe the
 * database from a specific pre-{@code V13} starting point, which a container shared across tests
 * cannot give cleanly.
 */
@Testcontainers
@EnabledIf(value = "dockerAvailable", disabledReason = "docker is not available in this environment")
class UpgradePathMigrationTest {

    @Container
    private final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    private EntityManagerFactory entityManagerFactory;

    @AfterEach
    void close() {
        if (entityManagerFactory != null) {
            entityManagerFactory.close();
        }
    }

    static boolean dockerAvailable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }

    @Test
    void upgradeSucceedsAndLeavesPreExistingAccountsUntouched() throws Exception {
        // Defect 1, reproduced then fixed: before V90001's per-account NOT EXISTS guard, this
        // migrate() call raised "duplicate key value violates unique constraint users_username_key"
        // on the pre-existing 'admin' row (id differs, but username is already taken -- an
        // ON CONFLICT (id) target does not cover that) and PersistenceUnit.start never returned.
        LegacyState legacy = seedAuthSeedRunnerShapedState();

        entityManagerFactory = PersistenceUnit.start(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword(), true);

        UserRepositoryPort users = userRepository();
        User admin = users.findById(legacy.adminId()).orElseThrow();
        User manager = users.findById(legacy.managerId()).orElseThrow();
        User pilot = users.findById(legacy.pilotId()).orElseThrow();

        // The operator's own accounts: same ids, same (hand-inserted, pre-existing) hashes -- not
        // silently replaced by V90001's seeded admin/manager/pilot rows.
        assertEquals(legacy.adminId(), admin.id());
        assertEquals("legacy-admin-hash", admin.passwordHash());
        assertEquals(legacy.managerId(), manager.id());
        assertEquals("legacy-manager-hash", manager.passwordHash());
        assertEquals(legacy.pilotId(), pilot.id());
        assertEquals("legacy-pilot-hash", pilot.passwordHash());

        // Exactly the three pre-existing accounts: V90001's guard skipped all three inserts (their
        // usernames were already taken, even though none of their ids collided) -- not 6 total.
        assertEquals(3, users.findAll().size());
    }

    @Test
    void upgradeRestoresManagerVisibilityOfDevPrincipalOwnedAssets() throws Exception {
        LegacyState legacy = seedAuthSeedRunnerShapedState();

        entityManagerFactory = PersistenceUnit.start(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword(), true);

        GroupRepositoryPort groups = groupRepository();
        Group fixedGroup = groups.findById(devPrincipalGroupId()).orElseThrow();
        assertEquals(legacy.rootGroupId(), fixedGroup.parentGroupId(),
                "V16 must adopt the fixed group as a child of the pre-existing root");
        assertEquals("Dev-Mode Assets", fixedGroup.name());

        User manager = userRepository().findById(legacy.managerId()).orElseThrow();

        // The real resolver, the real subtree walk -- not a hand-simulated approximation of
        // DefaultScopeResolver's logic.
        DefaultScopeResolver resolver =
                new DefaultScopeResolver(groups, new JpaAssignmentRepository(entityManagerFactory));
        VisibilityScope scope = resolver.scopeFor(manager);

        // This is the assertion that proves the original bug is actually fixed: an asset stamped
        // with DevPrincipal's own group (UUID(0,1)) must now be visible to a manager whose
        // membership only ever pointed at the OLD random root -- that group id is restated locally
        // rather than imported from vision-app's DevPrincipal, since storage/persistence must not
        // depend on vision-app (see devPrincipalGroupId()'s own javadoc, and
        // V13__identity_baseline.sql's header for the same restatement).
        boolean sees = scope.includes(new AssetId(UUID.randomUUID()),
                new Ownership(new UserId(UUID.randomUUID()), devPrincipalGroupId()));
        assertTrue(sees, "a manager scoped to the pre-existing root must now see DevPrincipal-owned assets");
    }

    @Test
    void freshInstallLeavesFixedGroupParentlessWithV13NameAndSeedsTheThreeDevAccounts() {
        entityManagerFactory = PersistenceUnit.start(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword(), true);

        Group fixedGroup = groupRepository().findById(devPrincipalGroupId()).orElseThrow();
        assertNull(fixedGroup.parentGroupId(), "V16 must be a no-op when zero other parentless groups exist");
        assertEquals("Root", fixedGroup.name(), "V13's name must survive an unaffected V16");
        assertEquals(1, groupRepository().findAll().size());

        assertTrue(userRepository().findById(new UserId(new UUID(0, 0))).isPresent(), "admin");
        assertTrue(userRepository().findById(new UserId(new UUID(0, 2))).isPresent(), "manager");
        assertTrue(userRepository().findById(new UserId(new UUID(0, 3))).isPresent(), "pilot");
    }

    @Test
    void runningTheFullMigrationSetTwiceChangesNothing() {
        entityManagerFactory = PersistenceUnit.start(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword(), true);
        entityManagerFactory.close();

        // A fresh EntityManagerFactory -- a fresh Flyway.migrate() call -- against the same,
        // already-fully-migrated database. V16 is a versioned migration, so Flyway would not
        // re-run it even if it weren't already idempotent by construction; this proves the whole
        // chain (V13-V16 + V90001) settles into a fixed point, not just that one migration does.
        entityManagerFactory = PersistenceUnit.start(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword(), true);

        Group fixedGroup = groupRepository().findById(devPrincipalGroupId()).orElseThrow();
        assertNull(fixedGroup.parentGroupId());
        assertEquals("Root", fixedGroup.name());
        assertEquals(1, groupRepository().findAll().size());
        assertEquals(3, userRepository().findAll().size());
    }

    /** The ids/hashes an {@code AuthSeedRunner}-seeded database has: random throughout. */
    private record LegacyState(GroupId rootGroupId, UserId adminId, UserId managerId, UserId pilotId) {
    }

    /**
     * Migrates only {@code classpath:db/migration} up to {@code V12} (a real {@link Flyway} handle,
     * not {@link PersistenceUnit#start}, which always migrates to the latest resolvable version),
     * then hand-inserts a group + three users via plain JDBC shaped exactly like the deleted
     * {@code AuthSeedRunner} used to leave them: a "Root" group at a random id, and
     * admin/manager/pilot at random ids whose {@code memberships} point at that random root.
     */
    private LegacyState seedAuthSeedRunnerShapedState() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target("12")
                .load()
                .migrate();

        UUID rootGroupId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID managerId = UUID.randomUUID();
        UUID pilotId = UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO groups (id, name, parent_id) VALUES ('%s', 'Legacy Root', NULL)
                    """.formatted(rootGroupId));
            statement.execute("""
                    INSERT INTO users (id, username, display_name, email, password_hash, enabled, memberships)
                    VALUES ('%s', 'admin', 'Administrator', 'admin@vision.local', 'legacy-admin-hash', true,
                            '[{"groupId":{"value":"%s"},"role":"ADMIN"}]'::jsonb)
                    """.formatted(adminId, rootGroupId));
            statement.execute("""
                    INSERT INTO users (id, username, display_name, email, password_hash, enabled, memberships)
                    VALUES ('%s', 'manager', 'Manager', 'manager@vision.local', 'legacy-manager-hash', true,
                            '[{"groupId":{"value":"%s"},"role":"MANAGER"}]'::jsonb)
                    """.formatted(managerId, rootGroupId));
            statement.execute("""
                    INSERT INTO users (id, username, display_name, email, password_hash, enabled, memberships)
                    VALUES ('%s', 'pilot', 'Pilot', 'pilot@vision.local', 'legacy-pilot-hash', true,
                            '[{"groupId":{"value":"%s"},"role":"PILOT"}]'::jsonb)
                    """.formatted(pilotId, rootGroupId));
        }

        return new LegacyState(new GroupId(rootGroupId), new UserId(adminId), new UserId(managerId),
                new UserId(pilotId));
    }

    /**
     * {@code DevPrincipal.GROUP_ID} (station/vision-app devsupport, {@code UUID(0,1)}) restated
     * locally — {@code storage/persistence} must not depend on {@code vision-app} (the dependency
     * rule runs the other way), same restatement {@code V13__identity_baseline.sql}'s own header
     * already makes for this exact id.
     */
    private static GroupId devPrincipalGroupId() {
        return new GroupId(new UUID(0, 1));
    }

    private UserRepositoryPort userRepository() {
        return new JpaUserRepository(entityManagerFactory);
    }

    private GroupRepositoryPort groupRepository() {
        return new JpaGroupRepository(entityManagerFactory);
    }
}

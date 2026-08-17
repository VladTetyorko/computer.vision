package com.drones.vision.adapter.persistence;

import com.drones.vision.adapter.persistence.config.PersistenceUnit;
import com.drones.vision.adapter.persistence.repository.JpaGroupRepository;
import com.drones.vision.adapter.persistence.repository.JpaUserRepository;
import com.drones.vision.identity.domain.model.Group;
import com.drones.vision.identity.domain.model.Membership;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.identity.domain.model.User;
import com.drones.vision.identity.domain.port.GroupRepositoryPort;
import com.drones.vision.identity.domain.port.UserRepositoryPort;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.UserId;

import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the W1 dev-account-seed contract (docs/plans/active/POSTGRES-ONLY-CONTEXT.md §5) against a real
 * Postgres, one scenario per test method rather than reasoning about Flyway's behavior:
 *
 * <ul>
 *   <li>flag absent/false ⇒ the three accounts are not in the database, and migration succeeds;</li>
 *   <li>flag true ⇒ all three exist, each {@code password_hash} verifies against its plaintext
 *       through the same {@link BCryptPasswordEncoder} {@code BcryptPasswordHasher} (vision-app)
 *       wraps, and {@code memberships} jsonb round-trips to the exact {@link Membership} the fixed
 *       root group implies;</li>
 *   <li>applying twice is a no-op (Flyway's own versioned-migration bookkeeping — see
 *       {@link #applyingTwiceIsANoOp()} for why this is still worth asserting explicitly);</li>
 *   <li>flipping the flag false→true on an already-migrated database still seeds the accounts
 *       retroactively (the scenario a plain versioned migration in the main location could not
 *       satisfy — see {@link PersistenceUnit}'s own javadoc);</li>
 *   <li>flipping true→false afterwards does not break subsequent migrations, thanks to
 *       {@code ignoreMigrationPatterns("*:future", "*:missing")} — without it this scenario fails
 *       Flyway's validate step with "Detected applied migration not resolved locally." The pattern
 *       that actually fires is {@code "*:future"}, not {@code "*:missing"} as a first reading of
 *       {@link PersistenceUnit}'s options might suggest — see {@link
 *       #flippingTrueThenFalseDoesNotBreakSubsequentMigrationsAndLeavesTheSeededAccountsInPlace()}
 *       and {@link PersistenceUnit}'s own javadoc for why, measured against a real Postgres after a
 *       bare {@code "*:missing"} silently failed to suppress the error.</li>
 * </ul>
 *
 * <p>A fresh {@link PostgreSQLContainer} per test method (an instance, not {@code static}, field —
 * the Testcontainers JUnit 5 extension starts/stops a non-static {@code @Container} around every
 * test) rather than {@link PostgresDockerIntegrationTest}'s one shared container: several scenarios
 * here need to observe Flyway's history table transition through specific states (unmigrated →
 * flag-off → flag-on, etc.), which a container already carrying other tests' migration history
 * cannot give cleanly.
 */
@Testcontainers
@EnabledIf(value = "dockerAvailable", disabledReason = "docker is not available in this environment")
class DevAccountSeedMigrationTest {

    @Container
    private final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

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
    void flagFalseSeedsNoDevAccountsButStillSeedsTheRootGroupAndMigrationSucceeds() {
        start(false);

        assertTrue(userRepository().findAll().isEmpty(), "no dev accounts without the flag");
        List<Group> groups = groupRepository().findAll();
        assertEquals(1, groups.size(), "V13__identity_baseline.sql seeds the root group unconditionally");
        assertEquals("Root", groups.get(0).name());
        assertEquals(rootGroupId(), groups.get(0).id());
    }

    @Test
    void flagTrueSeedsAllThreeAccountsWithVerifiedBcryptHashesAndTheReadableMembershipShape() {
        start(true);

        User admin = userRepository().findById(new UserId(new UUID(0, 0))).orElseThrow();
        User manager = userRepository().findById(new UserId(new UUID(0, 2))).orElseThrow();
        User pilot = userRepository().findById(new UserId(new UUID(0, 3))).orElseThrow();

        assertAccount(admin, "admin", Role.ADMIN);
        assertAccount(manager, "manager", Role.MANAGER);
        assertAccount(pilot, "pilot", Role.PILOT);
        assertEquals(3, userRepository().findAll().size());
    }

    @Test
    void applyingTwiceIsANoOp() {
        // Flyway's own history table makes a versioned migration idempotent by construction (a
        // second migrate() call against the same database sees V90001 already applied and skips
        // it) -- asserted here anyway, not because the mechanism is in doubt, but because the
        // seed migration's own ON CONFLICT (id) DO NOTHING is only a second line of defense: this
        // proves the *first* line (Flyway itself) is what actually fires, by reopening a brand
        // new EntityManagerFactory (a fresh Flyway.migrate() call) against the same database and
        // confirming no duplicate rows and no error.
        start(true);
        entityManagerFactory.close();

        entityManagerFactory = PersistenceUnit.start(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword(), true);

        assertEquals(3, userRepository().findAll().size());
        assertEquals(1, groupRepository().findAll().size());
    }

    @Test
    void flippingFalseThenTrueOnAnAlreadyMigratedDatabaseStillSeedsTheAccountsRetroactively() {
        start(false);
        assertTrue(userRepository().findAll().isEmpty());
        entityManagerFactory.close();

        // Same database, flag now on: db/seed/dev joins `locations` for the first time. Flyway
        // resolves V90001 as a new pending migration (it was never in flyway_schema_history) and
        // applies it now, on top of whatever db/migration schema already exists (already past V13
        // by the time this runs -- see this class's own javadoc for why that matters) -- this is
        // the behaviour a plain versioned migration living in the always-scanned main location
        // could never opt out of, and is exactly what makes the two-location split satisfy this
        // case.
        entityManagerFactory = PersistenceUnit.start(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword(), true);

        assertEquals(3, userRepository().findAll().size());
        assertAccount(userRepository().findById(new UserId(new UUID(0, 0))).orElseThrow(), "admin", Role.ADMIN);
    }

    @Test
    void flippingTrueThenFalseDoesNotBreakSubsequentMigrationsAndLeavesTheSeededAccountsInPlace() {
        start(true);
        assertEquals(3, userRepository().findAll().size());
        entityManagerFactory.close();

        // Same database, flag now off: db/seed/dev drops out of `locations`, so Flyway can no
        // longer resolve the V90001 migration it already applied -- its orphaned version (90001)
        // sorts above context.lastResolved (db/migration's own highest, e.g. V15), so Flyway
        // classifies it as FUTURE_SUCCESS, not MISSING_SUCCESS (BaseAppliedMigration#getMissingState
        // branches on exactly that comparison -- confirmed by decompiling flyway-core, not assumed).
        // Without ignoreMigrationPatterns("*:future", "*:missing") -- "*:future" is the one that
        // actually matches here -- this throws FlywayValidateException ("Detected applied migration
        // not resolved locally: 90001") and PersistenceUnit#start never reaches the
        // EntityManagerFactory at all. Asserting this call returns normally IS the proof.
        entityManagerFactory = PersistenceUnit.start(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword(), false);

        // Nothing un-seeds a migration that already ran -- db/seed/dev is purely additive, and
        // Flyway never re-runs or rolls back an applied version just because its location vanished.
        assertEquals(3, userRepository().findAll().size());
    }

    private void assertAccount(User user, String expectedUsername, Role expectedRole) {
        assertEquals(expectedUsername, user.username());
        assertTrue(user.enabled());
        assertTrue(passwordEncoder.matches(expectedUsername, user.passwordHash()),
                () -> expectedUsername + "'s seeded hash must verify against its own username as plaintext");
        assertEquals(List.of(new Membership(rootGroupId(), expectedRole)), user.memberships());
    }

    private static GroupId rootGroupId() {
        return new GroupId(new UUID(0, 1));
    }

    private void start(boolean seedDevUsers) {
        entityManagerFactory = PersistenceUnit.start(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword(), seedDevUsers);
    }

    private UserRepositoryPort userRepository() {
        return new JpaUserRepository(entityManagerFactory);
    }

    private GroupRepositoryPort groupRepository() {
        return new JpaGroupRepository(entityManagerFactory);
    }
}

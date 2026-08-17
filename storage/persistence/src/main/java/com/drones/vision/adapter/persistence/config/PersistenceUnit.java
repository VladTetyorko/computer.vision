package com.drones.vision.adapter.persistence.config;

import com.drones.vision.adapter.persistence.entity.AssetEntity;
import com.drones.vision.adapter.persistence.entity.AssetImageEntity;
import com.drones.vision.adapter.persistence.entity.AssetUsageEntity;
import com.drones.vision.adapter.persistence.entity.AssignmentEntity;
import com.drones.vision.adapter.persistence.entity.AuditEntryEntity;
import com.drones.vision.adapter.persistence.entity.CategoryEntity;
import com.drones.vision.adapter.persistence.entity.DatasetEntity;
import com.drones.vision.adapter.persistence.entity.DetectionEventEntity;
import com.drones.vision.adapter.persistence.entity.DetectionResultEntity;
import com.drones.vision.adapter.persistence.entity.DeviceEntity;
import com.drones.vision.adapter.persistence.entity.GeofenceZoneEntity;
import com.drones.vision.adapter.persistence.entity.GroupEntity;
import com.drones.vision.adapter.persistence.entity.MapDrawingEntity;
import com.drones.vision.adapter.persistence.entity.MapLayerEntity;
import com.drones.vision.adapter.persistence.entity.MarkEntity;
import com.drones.vision.adapter.persistence.entity.SampleImageEntity;
import com.drones.vision.adapter.persistence.entity.TelemetrySampleEntity;
import com.drones.vision.adapter.persistence.entity.TrainingSampleEntity;
import com.drones.vision.adapter.persistence.entity.UserEntity;

import jakarta.persistence.EntityManagerFactory;

import org.flywaydb.core.Flyway;
import org.hibernate.cfg.Configuration;

/**
 * Boots the persistence unit backing every {@code Jpa*Repository} in this module: migrates the
 * schema with Flyway ({@code classpath:db/migration}), then opens a Hibernate {@link
 * EntityManagerFactory} against the same database.
 *
 * <p>No connection pool and no Spring involved: {@link #start} uses Hibernate's native bootstrap
 * API ({@link Configuration}, whose {@link Configuration#buildSessionFactory()} return type —
 * {@link org.hibernate.SessionFactory} — implements {@link EntityManagerFactory} directly, so
 * every {@code Jpa*Repository} still only ever calls standard {@code jakarta.persistence} API)
 * and its default {@code DriverManager}-based connection provider (one JDBC connection per
 * {@code EntityManager}, opened/closed per call — see {@link JpaOperations}). Adequate for this
 * platform's single-instance scope; swapping in a pooled provider (e.g. HikariCP, already on the
 * classpath transitively via Hibernate's own dependencies) is a config-only change if concurrency
 * ever demands it — see MODULE.md's Gotchas.
 *
 * <p>Every value {@link #start} sets on {@link Configuration} is either a caller-supplied
 * argument (JDBC URL/user/password) or a fixed protocol/design constant (the JDBC driver class,
 * the SQL dialect, {@code hibernate.hbm2ddl.auto=validate}) — there is no hardcoded
 * connection-pool sizing, batch size, or timeout here to externalize into a settings record
 * (docs/plans/active/LAYERING-REFACTOR-PLAN.md §3/§7 row C item 5): this module has no framework dependency of
 * its own to read such a setting from, and none of these three properties varies per environment
 * the way a pool size or timeout would.
 *
 * <p><strong>Dev-account seeding ({@code seedDevUsers})</strong> — docs/plans/active/POSTGRES-ONLY-CONTEXT.md
 * W1: {@code classpath:db/seed/dev} (today, one migration — {@code V90001__dev_accounts.sql}, the
 * DEV-ONLY {@code admin}/{@code manager}/{@code pilot} accounts) is a Flyway location Flyway only
 * ever sees when {@code seedDevUsers} is {@code true}, so a database migrated with the flag off
 * never has those rows at all. {@code ignoreMigrationPatterns("*:future", "*:missing")} is required
 * for the *other* direction — a database migrated once with the flag on, then restarted with it
 * off, still carries that migration's row in {@code flyway_schema_history} even though its location
 * has disappeared from {@link #start}'s {@code locations} call; without an ignore pattern that
 * covers it, Flyway's own validate step (which always runs as part of {@code migrate()}) fails the
 * whole boot with "Detected applied migration not resolved locally" instead of just leaving the
 * already-seeded rows alone and migrating everything else normally. {@code "*:future"} is the one
 * that actually fires: {@code V90001}'s reserved-high-band version (see that file's own header for
 * why) always sorts above whatever {@code classpath:db/migration} last resolved to once
 * {@code db/seed/dev} drops out of {@code locations}, and Flyway classifies an orphaned
 * higher-versioned applied migration as {@code FUTURE_SUCCESS}, not {@code MISSING_SUCCESS} —
 * confirmed by decompiling {@code BaseAppliedMigration#getMissingState}, which branches purely on
 * the orphaned version compared to {@code context.lastResolved}. {@code "*:future"} is also Flyway's
 * own built-in default ({@code FlywayModel}'s constructor sets it before any caller-supplied value);
 * calling {@code ignoreMigrationPatterns(...)} at all replaces that default outright, so it has to
 * be restated here alongside {@code "*:missing"} (kept defensively, in case a future re-numbering
 * ever put {@code V90001} below {@code db/migration}'s highest version) or the seed-then-unseed case
 * regresses silently. Both directions (flag off→on retroactively seeds; flag on→off doesn't break
 * subsequent migrations) are proven against a real Postgres in {@code DevAccountSeedMigrationTest},
 * not just reasoned about — see that class for why a plain versioned migration in the main location
 * could not satisfy the off→on case, and why the fix above needed a real Postgres run to catch (the
 * pattern that "should" work by a first reading of {@code ignoreMigrationPatterns}' docs — a bare
 * {@code "*:missing"} — silently matched nothing here, because the actual runtime state was
 * {@code FUTURE_SUCCESS}, not {@code MISSING_SUCCESS}).
 */
public final class PersistenceUnit {

    private PersistenceUnit() {
    }

    /**
     * {@link #start(String, String, String, boolean)} with dev-account seeding off — the shape
     * every caller used before docs/plans/active/POSTGRES-ONLY-CONTEXT.md W1 introduced the flag, kept so
     * existing callers (e.g. {@code PostgresDockerIntegrationTest}) don't need to change.
     *
     * @param jdbcUrl  JDBC URL, e.g. {@code jdbc:postgresql://localhost:5432/vision}
     * @param username database user
     * @param password database password
     * @return an open {@link EntityManagerFactory}; the caller owns its lifecycle and must
     *         {@code close()} it on shutdown
     */
    public static EntityManagerFactory start(String jdbcUrl, String username, String password) {
        return start(jdbcUrl, username, password, false);
    }

    /**
     * Migrates the schema then opens an {@link EntityManagerFactory} mapping every entity in
     * {@link com.drones.vision.adapter.persistence.entity}.
     *
     * @param jdbcUrl      JDBC URL, e.g. {@code jdbc:postgresql://localhost:5432/vision}
     * @param username     database user
     * @param password     database password
     * @param seedDevUsers whether to also apply {@code classpath:db/seed/dev}'s DEV-ONLY
     *                     {@code admin}/{@code manager}/{@code pilot} accounts — see this class's
     *                     own javadoc. Only ever {@code true} when an operator explicitly opted in
     *                     ({@code vision.persistence.seed-dev-users=true}); default {@code false}.
     * @return an open {@link EntityManagerFactory}; the caller owns its lifecycle and must
     *         {@code close()} it on shutdown
     */
    public static EntityManagerFactory start(String jdbcUrl, String username, String password,
                                              boolean seedDevUsers) {
        String[] locations = seedDevUsers
                ? new String[] {"classpath:db/migration", "classpath:db/seed/dev"}
                : new String[] {"classpath:db/migration"};
        Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations(locations)
                // See this class's own javadoc ("Dev-account seeding") for why this is required —
                // a database seeded while seedDevUsers was true still carries db/seed/dev's
                // migration in its history after a restart with the flag off. "*:future" is
                // Flyway's OWN default ignore pattern (ClassicConfiguration's built-in default,
                // confirmed by decompiling flyway-core -- Flyway ships this exact tolerance out
                // of the box); calling ignoreMigrationPatterns(...) at all replaces that default
                // outright, so it must be restated here or the seed-then-unseed case regresses.
                // "*:missing" is added defensively for the same case; "*:future" is the one that
                // actually fires today because V90001's reserved-high-band version always sorts
                // above db/migration's own highest resolved version once its own location drops
                // out of `locations` -- see V90001__dev_accounts.sql's header for why Flyway
                // classifies an orphaned higher-versioned migration as MISSING_SUCCESS's sibling
                // FUTURE_SUCCESS, not MISSING_SUCCESS itself (BaseAppliedMigration#getMissingState
                // branches purely on the orphaned version vs. context.lastResolved).
                .ignoreMigrationPatterns("*:future", "*:missing")
                .load()
                .migrate();

        Configuration configuration = new Configuration();
        configuration.setProperty("jakarta.persistence.jdbc.url", jdbcUrl);
        configuration.setProperty("jakarta.persistence.jdbc.user", username);
        configuration.setProperty("jakarta.persistence.jdbc.password", password);
        configuration.setProperty("jakarta.persistence.jdbc.driver", "org.postgresql.Driver");
        configuration.setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        // Flyway owns the schema; Hibernate only ever validates its entity mapping against it.
        configuration.setProperty("hibernate.hbm2ddl.auto", "validate");
        configuration.addAnnotatedClass(CategoryEntity.class);
        configuration.addAnnotatedClass(DeviceEntity.class);
        configuration.addAnnotatedClass(AssetEntity.class);
        configuration.addAnnotatedClass(AssetUsageEntity.class);
        configuration.addAnnotatedClass(TelemetrySampleEntity.class);
        configuration.addAnnotatedClass(DetectionResultEntity.class);
        configuration.addAnnotatedClass(AssetImageEntity.class);
        configuration.addAnnotatedClass(GeofenceZoneEntity.class);
        configuration.addAnnotatedClass(UserEntity.class);
        configuration.addAnnotatedClass(GroupEntity.class);
        configuration.addAnnotatedClass(AssignmentEntity.class);
        configuration.addAnnotatedClass(MarkEntity.class);
        configuration.addAnnotatedClass(DatasetEntity.class);
        configuration.addAnnotatedClass(TrainingSampleEntity.class);
        configuration.addAnnotatedClass(SampleImageEntity.class);
        // docs/plans/done/MAP-REWORK-PLAN.md Wave C (V12__map_layers.sql). LayerGrantEmbeddable needs no
        // registration of its own -- Hibernate discovers an @Embeddable through the @ElementCollection
        // field that uses it, unlike an @Entity, which must be named explicitly here.
        configuration.addAnnotatedClass(MapLayerEntity.class);
        configuration.addAnnotatedClass(MapDrawingEntity.class);
        // docs/plans/active/POSTGRES-ONLY-CONTEXT.md W3 (V14__audit_trail.sql, V15__detection_events.sql).
        configuration.addAnnotatedClass(AuditEntryEntity.class);
        configuration.addAnnotatedClass(DetectionEventEntity.class);
        return configuration.buildSessionFactory();
    }
}

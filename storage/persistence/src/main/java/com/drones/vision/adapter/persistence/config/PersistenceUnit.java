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
import com.drones.vision.adapter.persistence.entity.FeatureRequirementEntity;
import com.drones.vision.adapter.persistence.entity.GeofenceZoneEntity;
import com.drones.vision.adapter.persistence.entity.GroupEntity;
import com.drones.vision.adapter.persistence.entity.MapDrawingEntity;
import com.drones.vision.adapter.persistence.entity.MapLayerEntity;
import com.drones.vision.adapter.persistence.entity.MarkEntity;
import com.drones.vision.adapter.persistence.entity.SampleImageEntity;
import com.drones.vision.adapter.persistence.entity.TelemetrySampleEntity;
import com.drones.vision.adapter.persistence.entity.TrainingSampleEntity;
import com.drones.vision.adapter.persistence.entity.UserEntity;
import com.drones.vision.adapter.persistence.entity.VehicleProfileEntity;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import jakarta.persistence.EntityManagerFactory;

import org.flywaydb.core.Flyway;
import org.hibernate.cfg.Configuration;

/**
 * Boots the persistence unit backing every {@code Jpa*Repository} in this module: migrates the
 * schema with Flyway ({@code classpath:db/migration}), then opens a Hibernate {@link
 * EntityManagerFactory} against the same database.
 *
 * <p>Hibernate is bootstrapped via its native API ({@link Configuration}, whose {@link
 * Configuration#buildSessionFactory()} return type — {@link org.hibernate.SessionFactory} —
 * implements {@link EntityManagerFactory} directly, so every {@code Jpa*Repository} still only
 * ever calls standard {@code jakarta.persistence} API) and no Spring. {@link #start} builds
 * exactly one pooled {@code javax.sql.DataSource} (HikariCP, sized by {@link
 * PersistencePoolSettings}) and hands that same instance to both Flyway's migration connection
 * and Hibernate's {@link ClosingDatasourceConnectionProvider} — one shared, bounded pool for
 * migration and every request, replacing Hibernate's built-in {@code
 * DriverManagerConnectionProvider}, which used to open a fresh, unpooled JDBC connection per
 * {@code EntityManager} (see {@link JpaOperations}) and logged {@code HHH10001002: Using built-in
 * connection pool (not intended for production use)} at every startup.
 * <strong>Correction:</strong> a prior version of this paragraph claimed HikariCP was "already on
 * the classpath transitively via Hibernate's own dependencies" and that adding it was "a
 * config-only change" — both were false, verified by {@code mvn -pl storage/persistence
 * dependency:list} returning no pool library at all (docs/plans/active/SCALE-100-PLAN.md §2 g2,
 * 2026-08-17). {@code com.zaxxer:HikariCP} had to be added to {@code pom.xml} and this class had
 * to change, which is what this paragraph and {@link ClosingDatasourceConnectionProvider}'s own
 * javadoc now document — including why the provider is a hand-written {@code DataSource} wrapper
 * rather than Hibernate's own {@code HikariCPConnectionProvider} (that class always builds a
 * second, unshared pool, so {@code hibernate-hikaricp} is not a dependency here at all).
 *
 * <p>Every value {@link #start} sets on {@link Configuration} is either a caller-supplied
 * argument (JDBC URL/user/password), a fixed protocol/design constant (the JDBC driver class, the
 * SQL dialect, the connection-provider class name, {@code hibernate.hbm2ddl.auto=validate} — none
 * of these vary per environment the way a pool size or timeout does), or read straight through
 * from the caller-supplied {@link PersistencePoolSettings} (CLAUDE.md rule 1: no hardcoded pool
 * sizing or timeout literal lives in this class).
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

    /** The JDBC driver Postgres always uses here — a fixed protocol constant, not a setting. */
    private static final String JDBC_DRIVER_CLASS_NAME = "org.postgresql.Driver";

    /**
     * Identifies this pool in HikariCP's own logging/metrics — a fixed identity constant (this
     * module runs exactly one pool), not a per-environment setting.
     */
    private static final String POOL_NAME = "vision-persistence";

    /**
     * {@link #start(String, String, String, boolean, PersistencePoolSettings)} with dev-account
     * seeding off and default pool sizing — the shape every caller used before
     * docs/plans/active/POSTGRES-ONLY-CONTEXT.md W1 introduced the seeding flag, kept so existing callers
     * (e.g. {@code PostgresDockerIntegrationTest}) don't need to change.
     *
     * @param jdbcUrl  JDBC URL, e.g. {@code jdbc:postgresql://localhost:5432/vision}
     * @param username database user
     * @param password database password
     * @return an open {@link EntityManagerFactory}; the caller owns its lifecycle and must
     *         {@code close()} it on shutdown — closing it also closes the connection pool
     *         underneath it, see {@link ClosingDatasourceConnectionProvider}
     */
    public static EntityManagerFactory start(String jdbcUrl, String username, String password) {
        return start(jdbcUrl, username, password, false, PersistencePoolSettings.defaults());
    }

    /**
     * {@link #start(String, String, String, boolean, PersistencePoolSettings)} with default pool
     * sizing — the shape every caller used before docs/plans/active/SCALE-100-PLAN.md S3 introduced pool
     * settings, kept so existing callers don't need to change.
     *
     * @param jdbcUrl      JDBC URL, e.g. {@code jdbc:postgresql://localhost:5432/vision}
     * @param username     database user
     * @param password     database password
     * @param seedDevUsers whether to also apply {@code classpath:db/seed/dev}'s DEV-ONLY
     *                     {@code admin}/{@code manager}/{@code pilot} accounts — see this class's
     *                     own javadoc. Only ever {@code true} when an operator explicitly opted in
     *                     ({@code vision.persistence.seed-dev-users=true}); default {@code false}.
     * @return an open {@link EntityManagerFactory}; the caller owns its lifecycle and must
     *         {@code close()} it on shutdown — closing it also closes the connection pool
     *         underneath it, see {@link ClosingDatasourceConnectionProvider}
     */
    public static EntityManagerFactory start(String jdbcUrl, String username, String password,
                                              boolean seedDevUsers) {
        return start(jdbcUrl, username, password, seedDevUsers, PersistencePoolSettings.defaults());
    }

    /**
     * Migrates the schema then opens an {@link EntityManagerFactory} mapping every entity in
     * {@link com.drones.vision.adapter.persistence.entity}, both riding the one pooled {@code
     * DataSource} this method builds from {@code poolSettings} (docs/plans/active/SCALE-100-PLAN.md S3).
     *
     * @param jdbcUrl      JDBC URL, e.g. {@code jdbc:postgresql://localhost:5432/vision}
     * @param username     database user
     * @param password     database password
     * @param seedDevUsers whether to also apply {@code classpath:db/seed/dev}'s DEV-ONLY
     *                     {@code admin}/{@code manager}/{@code pilot} accounts — see this class's
     *                     own javadoc. Only ever {@code true} when an operator explicitly opted in
     *                     ({@code vision.persistence.seed-dev-users=true}); default {@code false}.
     * @param poolSettings HikariCP sizing for the shared pool — see {@link PersistencePoolSettings}
     *                     for each field's default and why.
     * @return an open {@link EntityManagerFactory}; the caller owns its lifecycle and must
     *         {@code close()} it on shutdown — closing it also closes the connection pool
     *         underneath it, see {@link ClosingDatasourceConnectionProvider}
     */
    public static EntityManagerFactory start(String jdbcUrl, String username, String password,
                                              boolean seedDevUsers, PersistencePoolSettings poolSettings) {
        HikariDataSource dataSource = buildDataSource(jdbcUrl, username, password, poolSettings);

        String[] locations = seedDevUsers
                ? new String[] {"classpath:db/migration", "classpath:db/seed/dev"}
                : new String[] {"classpath:db/migration"};
        Flyway.configure()
                // Shares the same pooled DataSource Hibernate will use below (docs/plans/active/SCALE-100-PLAN.md
                // S3) instead of opening its own independent, unpooled JDBC connection — migration
                // and every subsequent request now draw from one bounded pool.
                .dataSource(dataSource)
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
        // Hands Hibernate the exact same pooled DataSource Flyway just migrated through, via
        // Hibernate's own supported "pre-built DataSource" property (a live object, not a JNDI
        // name — see AvailableSettings#DATASOURCE), and selects the provider that (a) understands
        // that property and (b) closes the pool when this EntityManagerFactory closes.
        configuration.getProperties().put("hibernate.connection.datasource", dataSource);
        configuration.setProperty("hibernate.connection.provider_class",
                ClosingDatasourceConnectionProvider.class.getName());
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
        // docs/plans/active/DRONE-ONBOARDING-PLAN.md O5 (V17__vehicle_profiles.sql, V18__feature_requirements.sql).
        configuration.addAnnotatedClass(VehicleProfileEntity.class);
        configuration.addAnnotatedClass(FeatureRequirementEntity.class);
        return configuration.buildSessionFactory();
    }

    /**
     * The one pooled {@code DataSource} {@link #start} shares between Flyway and Hibernate — see
     * this class's own javadoc for why sharing one pool (rather than each building its own) is
     * the point of docs/plans/active/SCALE-100-PLAN.md S3.
     */
    private static HikariDataSource buildDataSource(String jdbcUrl, String username, String password,
                                                      PersistencePoolSettings poolSettings) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPoolName(POOL_NAME);
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);
        hikariConfig.setDriverClassName(JDBC_DRIVER_CLASS_NAME);
        hikariConfig.setMaximumPoolSize(poolSettings.maximumPoolSize());
        hikariConfig.setMinimumIdle(poolSettings.minimumIdle());
        hikariConfig.setConnectionTimeout(poolSettings.connectionTimeoutMillis());
        hikariConfig.setLeakDetectionThreshold(poolSettings.leakDetectionThresholdMillis());
        return new HikariDataSource(hikariConfig);
    }
}

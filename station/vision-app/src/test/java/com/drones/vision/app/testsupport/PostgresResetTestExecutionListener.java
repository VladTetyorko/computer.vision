package com.drones.vision.app.testsupport;

import com.drones.vision.app.config.properties.VisionPersistenceProperties;

import jakarta.persistence.EntityManagerFactory;

import org.flywaydb.core.Flyway;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resets the shared Postgres database before every test class — not just every unique Spring
 * context — so a row one class's test writes can never leak into the next class's assertions, even
 * when Spring reuses the same cached {@link ApplicationContext} (and therefore the same database
 * connection) across several classes with byte-identical {@code @SpringBootTest} {@code
 * properties}, e.g. this module's four {@code *AuthEnabledTest} classes. Registered through {@code
 * META-INF/spring.factories} ({@code org.springframework.test.context.TestExecutionListener}), so
 * every context gets it with zero per-class annotation, merged with Spring's own default listeners
 * (no test class here declares {@code @TestExecutionListeners}, so the default {@code
 * MERGE_WITH_DEFAULTS} mode applies).
 *
 * <p>Reset = Flyway {@code clean()} then {@code migrate()}, not a table-by-table {@code TRUNCATE}:
 * {@code migrate()} re-applies every seed migration (V2's categories, V12's COP layer, ...) exactly
 * as freshly as a first boot, so seeded rows a test legitimately depends on survive the reset with
 * no list of "protected" tables to hand-maintain here as {@code storage/persistence} adds more of
 * them. Uses the exact same {@link VisionPersistenceProperties} bean (and therefore the exact same
 * {@code seed-dev-users}-conditional {@code classpath:db/seed/dev} location) the context's own
 * {@code persistenceEntityManagerFactory} bean was built from, so the reset can never disagree with
 * what that context actually configured.
 *
 * <p><strong>Runs from {@link #beforeTestMethod}, guarded to fire once per test class, deliberately
 * not from {@code beforeTestClass}.</strong> {@code TestExecutionListener.beforeTestClass} is invoked
 * from {@code SpringExtension.beforeAll()} — a JUnit 5 {@code BeforeAllCallback} — which the JUnit 5
 * platform always runs <em>before</em> the test class's own {@code @BeforeAll} static methods. Calling
 * {@link TestContext#getApplicationContext()} that early forces this module's first-ever load of a
 * given {@code @SpringBootTest} configuration to happen before {@code @BeforeAll} has run — and
 * several classes here ({@code TrackingAssociateE2ETest}, {@code CvDetectionE2ETest}, {@code
 * CvDetectionEndpointE2ETest}) start an in-process gRPC server in {@code @BeforeAll} and read its
 * port back out through {@code @DynamicPropertySource} (evaluated during that same context build).
 * Forcing the build early made those read a still-null static field and fail with a {@code
 * NullPointerException}, wrapped as "Failed to load ApplicationContext" — a real regression this
 * listener caused, not a pre-existing flake. {@code beforeTestMethod} is a {@code
 * BeforeEachCallback}, which JUnit 5 only ever invokes after both {@code @BeforeAll} and test-instance
 * construction (which is what actually triggers context loading, via {@code
 * SpringExtension#postProcessTestInstance}) have completed — so by the time this listener can see the
 * context here, it already exists, cached or not, with every {@code @BeforeAll} side effect
 * (including this one) already applied. The {@link #resetClasses} guard keeps the reset itself at
 * once-per-class granularity (matching the reset strategy's intent and cost) rather than once-per-
 * test-method, which {@code beforeTestMethod} alone would otherwise give it.
 *
 * <p>A silent no-op when the context has no {@link EntityManagerFactory} bean at all (persistence
 * disabled) — no test in this module currently does that, but nothing here should error if one
 * ever does. Never reached in a Docker-less run: {@link DockerGatedExecutionCondition} disables the
 * whole class before any {@code TestExecutionListener} callback is ever invoked for it.
 */
public class PostgresResetTestExecutionListener extends AbstractTestExecutionListener {

    /**
     * Test classes already reset this JVM run. Keyed by class, not by {@link ApplicationContext}:
     * several classes can share one cached context, and each of THEM still needs its own clean
     * slate (that is the whole point of this listener), while every test <em>method</em> within one
     * class must not re-trigger it.
     */
    private static final Set<Class<?>> resetClasses = ConcurrentHashMap.newKeySet();

    @Override
    public void beforeTestMethod(TestContext testContext) {
        if (!resetClasses.add(testContext.getTestClass())) {
            return;
        }

        ApplicationContext context = testContext.getApplicationContext();
        if (context.getBeanNamesForType(EntityManagerFactory.class).length == 0) {
            return;
        }

        VisionPersistenceProperties properties = context.getBean(VisionPersistenceProperties.class);
        String[] locations = properties.seedDevUsers()
                ? new String[] {"classpath:db/migration", "classpath:db/seed/dev"}
                : new String[] {"classpath:db/migration"};

        Flyway flyway = Flyway.configure()
                .dataSource(properties.jdbcUrl(), properties.username(), properties.password())
                .locations(locations)
                .cleanDisabled(false)
                // Mirrors PersistenceUnit.start's own ignore patterns (see that class's javadoc) —
                // irrelevant to a fresh clean()+migrate() today (clean() drops flyway_schema_history
                // too, so there is never a stale entry to ignore), kept for defensive parity so this
                // reset can never diverge from what production migration tolerates.
                .ignoreMigrationPatterns("*:future", "*:missing")
                .load();
        flyway.clean();
        flyway.migrate();
    }
}

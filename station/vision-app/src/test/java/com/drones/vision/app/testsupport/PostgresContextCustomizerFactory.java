package com.drones.vision.app.testsupport;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

import java.util.List;

/**
 * Points every {@code @SpringBootTest} context in this module at {@link SharedPostgresContainer}
 * instead of {@code application.yaml}'s {@code localhost:5432} default — with zero per-class
 * annotation, per docs/plans/active/POSTGRES-ONLY-CONTEXT.md W4. Registered through {@code
 * META-INF/spring.factories} ({@code org.springframework.test.context.ContextCustomizerFactory}),
 * the idiomatic Spring TestContext seam for customizing every context a module builds, auto-detected
 * by {@code AbstractTestContextBootstrapper} via {@code SpringFactoriesLoader} — the same mechanism
 * {@code TestExecutionListener}s use (see {@link PostgresResetTestExecutionListener}).
 *
 * <p>Never invoked in a Docker-less run: {@link DockerGatedExecutionCondition} disables the whole
 * test class before {@code SpringExtension} ever calls into the TestContext framework that would
 * reach this factory, so {@link SharedPostgresContainer}'s static initializer never fires either.
 */
public class PostgresContextCustomizerFactory implements ContextCustomizerFactory {

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass,
                                                       List<ContextConfigurationAttributes> configAttributes) {
        return new PostgresContextCustomizer();
    }

    /**
     * Deliberately stateless and equal to every other instance of itself: {@link
     * MergedContextConfiguration}'s cache key includes its set of {@link ContextCustomizer}s,
     * compared via {@link #equals}, and {@link #createContextCustomizer} above hands out a fresh
     * instance to every test class. A default (identity) {@code equals} would make every one of
     * this module's 34 {@code @SpringBootTest} classes look like a unique context configuration —
     * 34 full Spring Boot boots instead of the handful today's distinct {@code properties}
     * combinations actually need — silently, with no test failing to explain the slowdown. Every
     * instance customizes a context identically (the one shared container never changes), so
     * treating every instance as equal is simply the true statement, not a workaround.
     */
    private static final class PostgresContextCustomizer implements ContextCustomizer {

        /**
         * Production's pool defaults are sized for one long-lived JVM serving a hundred users; a test
         * run is the opposite shape — many short-lived contexts, each doing almost nothing at once.
         * Spring caches every distinct context configuration for the whole run rather than closing
         * it, so {@code min-idle=5} (the production default) means every cached context parks five
         * connections forever. Past ~20 cached contexts that reaches the container's stock
         * {@code max_connections=100} and the next context to boot dies in Flyway with
         * "FATAL: sorry, too many clients already" — a failure that lands on whichever test class
         * happened to boot last, never on the one that caused it.
         *
         * <p>Kept here rather than in a test {@code application.yaml} so it travels with the same
         * seam that already redirects the datasource: anything that owns "which database" should own
         * "how many connections to it".
         */
        private static final int TEST_POOL_MAX_SIZE = 4;
        private static final int TEST_POOL_MIN_IDLE = 1;

        @Override
        public void customizeContext(ConfigurableApplicationContext context,
                                      MergedContextConfiguration mergedConfig) {
            PostgreSQLContainer postgres = SharedPostgresContainer.INSTANCE;
            TestPropertyValues.of(
                    "vision.persistence.jdbc-url=" + postgres.getJdbcUrl(),
                    "vision.persistence.username=" + postgres.getUsername(),
                    "vision.persistence.password=" + postgres.getPassword(),
                    "vision.persistence.pool.max-size=" + TEST_POOL_MAX_SIZE,
                    "vision.persistence.pool.min-idle=" + TEST_POOL_MIN_IDLE
            ).applyTo(context);
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof PostgresContextCustomizer;
        }

        @Override
        public int hashCode() {
            return PostgresContextCustomizer.class.hashCode();
        }
    }
}

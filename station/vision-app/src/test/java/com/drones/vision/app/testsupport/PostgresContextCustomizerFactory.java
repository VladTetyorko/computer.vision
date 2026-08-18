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

        @Override
        public void customizeContext(ConfigurableApplicationContext context,
                                      MergedContextConfiguration mergedConfig) {
            PostgreSQLContainer postgres = SharedPostgresContainer.INSTANCE;
            TestPropertyValues.of(
                    "vision.persistence.jdbc-url=" + postgres.getJdbcUrl(),
                    "vision.persistence.username=" + postgres.getUsername(),
                    "vision.persistence.password=" + postgres.getPassword()
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

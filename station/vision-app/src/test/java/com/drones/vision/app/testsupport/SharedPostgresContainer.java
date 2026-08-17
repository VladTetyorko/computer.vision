package com.drones.vision.app.testsupport;

import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One Postgres container for the whole JVM — the "singleton container" pattern, not one per
 * {@code @SpringBootTest} class. This module has 34 of those; one container each would be started
 * (and torn down) 34 times over, which is both slow and the exact thing
 * docs/plans/active/POSTGRES-ONLY-CONTEXT.md W4 rules out ("34 containers would be unusable").
 *
 * <p>{@code postgres:16} to match {@code docker-compose.yml}'s pinned production image — this
 * suite's Flyway migrations and JPA entity mappings must be proven against the same engine version
 * production runs, not whichever tag {@code :latest} happens to resolve to today.
 *
 * <p>Started eagerly by the static initializer below and never stopped explicitly: Testcontainers'
 * Ryuk reaper removes it when this JVM (the Surefire fork) exits, the same unmanaged-container
 * lifecycle every other Testcontainers use in this repo relies on. Only ever touched from
 * {@link PostgresContextCustomizerFactory} and {@link PostgresResetTestExecutionListener}, both of
 * which only run for a {@code @SpringBootTest} class that {@link DockerGatedExecutionCondition} has
 * already confirmed has a reachable Docker daemon — so in a Docker-less run this class is never
 * loaded and the container is never started.
 */
final class SharedPostgresContainer {

    static final PostgreSQLContainer INSTANCE = start();

    private SharedPostgresContainer() {
    }

    private static PostgreSQLContainer start() {
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:16");
        container.start();
        return container;
    }
}

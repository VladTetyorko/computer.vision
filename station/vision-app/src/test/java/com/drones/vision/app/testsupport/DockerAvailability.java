package com.drones.vision.app.testsupport;

import org.testcontainers.DockerClientFactory;

/**
 * Whether a Docker daemon is reachable from this JVM, probed once and cached — every consumer in
 * this package ({@link DockerGatedExecutionCondition}, and by extension the container/reset
 * machinery it gates) shares one answer instead of repeating the probe per test class.
 *
 * <p>Same detection call as {@code storage/persistence}'s {@code PostgresDockerIntegrationTest}
 * ({@link DockerClientFactory#isDockerAvailable()}) — Testcontainers' own canonical availability
 * check, not a re-implemented {@code docker info} CLI probe.
 */
final class DockerAvailability {

    private static final boolean AVAILABLE = probe();

    private DockerAvailability() {
    }

    static boolean isAvailable() {
        return AVAILABLE;
    }

    private static boolean probe() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }
}

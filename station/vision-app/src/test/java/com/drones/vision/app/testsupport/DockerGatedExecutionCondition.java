package com.drones.vision.app.testsupport;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.util.Optional;

/**
 * Skips every {@code @SpringBootTest} class cleanly — not with 34 connection-refused failures —
 * when no Docker daemon is reachable. docs/plans/done/POSTGRES-ONLY-CONTEXT.md W4 made Postgres the
 * default (W2b then removed the {@code vision.persistence.enabled} flag entirely), so every one of
 * this module's {@code @SpringBootTest} classes needs the real Postgres {@link
 * PostgresContextCustomizerFactory} points it at; a developer without Docker must still get a
 * comprehensible, green-with-skips result, matching this repo's existing docker-dependent-test
 * posture ({@code PostgresDockerIntegrationTest}, {@code MediamtxDockerIntegrationTest}, ...), not
 * a wall of connection errors.
 *
 * <p>Auto-detected via JUnit 5's extension auto-detection ({@code
 * junit.jupiter.extensions.autodetection.enabled=true} in {@code
 * src/test/resources/junit-platform.properties}, plus this class's own {@code
 * META-INF/services/org.junit.jupiter.api.extension.Extension} entry) — every test in this module
 * gets this condition evaluated with zero per-class {@code @EnabledIf}/{@code @Tag} annotation.
 * {@link ExecutionCondition}s run before <em>any</em> other extension callback (including {@code
 * SpringExtension}'s), so a {@link ConditionEvaluationResult#disabled} verdict here keeps Spring
 * from ever trying to build that class's context — {@link PostgresContextCustomizerFactory} and
 * {@link PostgresResetTestExecutionListener} are never invoked, and {@link SharedPostgresContainer}
 * is never started, for a disabled class.
 *
 * <p>Scoped to {@code @SpringBootTest} specifically, not every test in this module: {@code
 * ArchitectureTest} and the plain-unit wiring tests ({@code PersistenceWiringConfigurationTest},
 * {@code SimulationResumeWiringConfigurationTest}, ...) build no Spring context and need no
 * database — gating them too would shrink Docker-less coverage for no reason. Every context-loading
 * test class in this module uses bare {@code @SpringBootTest}
 * today (verified by grep, docs/plans/done/POSTGRES-ONLY-CONTEXT.md W4's own report); a future class using
 * {@code @DataJpaTest}/{@code @WebMvcTest}/some other context-loading annotation instead would need
 * adding here too.
 */
public class DockerGatedExecutionCondition implements ExecutionCondition {

    private static final ConditionEvaluationResult ENABLED =
            ConditionEvaluationResult.enabled("not a @SpringBootTest class, or Docker is available");
    private static final ConditionEvaluationResult DISABLED_NO_DOCKER = ConditionEvaluationResult.disabled(
            "Postgres is unconditional now (docs/plans/done/POSTGRES-ONLY-CONTEXT.md W2b) — this "
                    + "@SpringBootTest class needs a real Postgres container, and no Docker daemon is "
                    + "reachable in this environment");

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Optional<Class<?>> testClass = context.getTestClass();
        if (testClass.isEmpty() || !AnnotatedElementUtils.hasAnnotation(testClass.get(), SpringBootTest.class)) {
            return ENABLED;
        }
        return DockerAvailability.isAvailable() ? ENABLED : DISABLED_NO_DOCKER;
    }
}

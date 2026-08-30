package com.drones.vision.app;

import com.drones.vision.api.controller.ModelRegistryController;
import com.drones.vision.learning.application.ModelRegistryService;
import com.drones.vision.learning.domain.port.ModelRegistryPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Context test for {@code vision.cv.enabled=true} <strong>and</strong> an explicit {@code
 * vision.cv.registry.enabled=false} (docs/plans/active/CV-SETTINGS-PLAN.md §5,
 * CV-SETTINGS-CONTEXT.md's W4-app → W5 handoff) — the escape hatch: proves a deployment can still
 * turn detection on without the registry coming along for free, the pre-W5 "registry stays
 * entirely absent" behavior {@link CvEnabledWiringTest}'s own
 * {@code cvOnlyConfigurationWiresTheModelRegistryByDefault} no longer exercises now that leaving
 * the registry key unset follows {@code vision.cv.enabled}.
 *
 * <p>{@code vision.publish.enabled=false}/{@code vision.live.enabled=false} for the same isolation
 * reasons as {@link CvEnabledWiringTest}.
 */
@SpringBootTest(properties = {
        "vision.publish.enabled=false",
        "vision.cv.enabled=true",
        "vision.cv.endpoint=localhost:59324",
        "vision.cv.registry.enabled=false",
        "vision.live.enabled=false"
})
class CvRegistryExplicitOptOutWiringTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void explicitOptOutKeepsTheModelRegistryEntirelyAbsent() {
        assertTrue(applicationContext.getBeansOfType(ModelRegistryController.class).isEmpty());
        assertTrue(applicationContext.getBeansOfType(ModelRegistryService.class).isEmpty());
        assertTrue(applicationContext.getBeansOfType(ModelRegistryPort.class).isEmpty());
    }
}

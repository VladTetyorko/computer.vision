package com.drones.vision.app;

import com.drones.vision.adapter.simulation.SimulatedTelemetrySource;
import com.drones.vision.adapter.simulation.SimulatedVideoSource;
import com.drones.vision.api.controller.SimulationController;
import com.drones.vision.simulation.application.SimulationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Context test for the <em>disabled</em> {@code vision.simulation.enabled} configuration — the
 * true production default (per {@link com.drones.vision.app.config.properties.VisionSimulationProperties#enabled()}'s
 * default of {@code false} — docs/plans/active/LINK-PAIRING-PLAN.md §4 row L2), explicitly
 * overridden back to {@code false} below since {@code station/vision-app/src/test/resources/
 * application.properties} flips the module-wide test default to {@code true} (every other
 * pre-existing full-context test needs the Playground's beans present; this is the one test whose
 * entire point is proving they are absent when the flag is genuinely off — the same "inlined
 * properties win" precedent {@code vision.auth.enabled} already established there). Asserts the
 * context still loads cleanly with the Playground's controller and simulated discovery-producer
 * beans entirely absent, so {@code POST /api/simulations} 404s like any other unmapped route rather
 * than 403ing — there is nothing to authorize, the feature itself is off. Mirrors {@link
 * TrainingDisabledWiringTest}'s own "absent entirely" guardrail and {@code getBeansOfType} technique
 * (a plain {@code @Autowired} field would fail the context entirely if the bean is genuinely
 * absent, defeating the point of this test).
 *
 * <p>{@link SimulationService} itself (the plain application service, not the controller or the
 * two discovery-producer adapters) is <em>not</em> gated — only "creating a new simulated asset"
 * (the controller) and "producing simulated discovery input" (the two adapters) are, per this
 * wave's own brief — so it is deliberately left off this test's absence list.
 *
 * <p>{@code vision.publish.enabled=false} for the same determinism reasons as {@link
 * TrainingDisabledWiringTest}.
 */
@SpringBootTest(properties = {"vision.publish.enabled=false", "vision.simulation.enabled=false"})
class SimulationDisabledWiringTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Test
    void noSimulationControllerBeanExistsByDefault() {
        assertTrue(applicationContext.getBeansOfType(SimulationController.class).isEmpty());
    }

    @Test
    void noSimulatedDiscoveryProducerBeansExistByDefault() {
        assertTrue(applicationContext.getBeansOfType(SimulatedVideoSource.class).isEmpty());
        assertTrue(applicationContext.getBeansOfType(SimulatedTelemetrySource.class).isEmpty());
    }

    @Test
    void postSimulationsIs404WhenTheFlagIsOff() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        mockMvc.perform(post("/api/simulations").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
    }
}

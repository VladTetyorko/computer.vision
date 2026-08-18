package com.drones.vision.app.config.wiring;

import com.drones.vision.app.config.properties.VisionSimulationProperties;
import com.drones.vision.simulation.application.SimulationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Plain unit test (no Spring context) for {@link ApplicationServiceWiring#simulationResumeRunner}.
 *
 * <p>Before docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b this method ANDed {@code
 * vision.persistence.enabled} together with {@link VisionSimulationProperties#resumeOnBoot()};
 * that flag is gone (Postgres is unconditional now), so this is a plain one-property
 * pass-through and there is only one gate left to prove.
 */
class SimulationResumeWiringConfigurationTest {

    private final ApplicationServiceWiring configuration = new ApplicationServiceWiring();

    @Test
    void resumesWhenResumeOnBootIsEnabled() throws Exception {
        assertResumes(true, true);
    }

    @Test
    void neverResumesWhenResumeOnBootIsDisabled() throws Exception {
        assertResumes(false, false);
    }

    private void assertResumes(boolean resumeOnBoot, boolean expectResume) throws Exception {
        SimulationService simulationService = mock(SimulationService.class);
        VisionSimulationProperties simulationProperties = new VisionSimulationProperties(resumeOnBoot);

        ApplicationRunner runner = configuration.simulationResumeRunner(simulationService, simulationProperties);
        runner.run(null);

        if (expectResume) {
            verify(simulationService).resumeAll();
        } else {
            verifyNoInteractions(simulationService);
        }
    }
}

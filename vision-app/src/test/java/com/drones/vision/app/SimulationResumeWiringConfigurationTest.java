package com.drones.vision.app;

import com.drones.vision.app.config.WiringConfiguration;
import com.drones.vision.application.SimulationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Plain unit test (no Spring context) for {@link WiringConfiguration#simulationResumeRunner}'s
 * two-property AND logic — mirrors {@link PersistenceWiringConfigurationTest}'s own reasoning for
 * calling a {@code @Bean} method directly rather than booting a real context: unlike {@code
 * vision.persistence.enabled=true} (which needs a reachable Postgres), nothing here strictly
 * requires that, but exercising all four gate combinations via a real {@code @SpringBootTest}
 * would mean four separate contexts just to prove a one-line boolean expression — this is simpler
 * and just as conclusive.
 */
class SimulationResumeWiringConfigurationTest {

    private final WiringConfiguration configuration = new WiringConfiguration();

    @Test
    void resumesWhenBothPersistenceAndResumeOnBootAreEnabled() throws Exception {
        assertResumes(true, true, true);
    }

    @Test
    void neverResumesWhenPersistenceIsDisabledEvenIfResumeOnBootIsEnabled() throws Exception {
        assertResumes(false, true, false);
    }

    @Test
    void neverResumesWhenResumeOnBootIsDisabledEvenIfPersistenceIsEnabled() throws Exception {
        assertResumes(true, false, false);
    }

    @Test
    void neverResumesWhenBothAreDisabled() throws Exception {
        assertResumes(false, false, false);
    }

    private void assertResumes(boolean persistenceEnabled, boolean resumeOnBoot, boolean expectResume) throws Exception {
        SimulationService simulationService = mock(SimulationService.class);
        VisionPersistenceProperties persistenceProperties = new VisionPersistenceProperties(persistenceEnabled,
                VisionPersistenceProperties.DEFAULT_JDBC_URL, "vision", "vision");
        VisionSimulationProperties simulationProperties = new VisionSimulationProperties(resumeOnBoot);

        ApplicationRunner runner =
                configuration.simulationResumeRunner(simulationService, persistenceProperties, simulationProperties);
        runner.run(null);

        if (expectResume) {
            verify(simulationService).resumeAll();
        } else {
            verifyNoInteractions(simulationService);
        }
    }
}

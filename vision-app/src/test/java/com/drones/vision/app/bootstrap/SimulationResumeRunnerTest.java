package com.drones.vision.app.bootstrap;

import com.drones.vision.simulation.application.SimulationService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Plain unit test (no Spring context) for {@link SimulationResumeRunner}.
 */
class SimulationResumeRunnerTest {

    @Test
    void runCallsResumeAllWhenEnabled() throws Exception {
        SimulationService simulationService = mock(SimulationService.class);
        SimulationResumeRunner runner = new SimulationResumeRunner(simulationService, true);

        runner.run(null);

        verify(simulationService).resumeAll();
    }

    @Test
    void runNeverTouchesSimulationServiceWhenDisabled() throws Exception {
        SimulationService simulationService = mock(SimulationService.class);
        SimulationResumeRunner runner = new SimulationResumeRunner(simulationService, false);

        runner.run(null);

        verifyNoInteractions(simulationService);
    }
}

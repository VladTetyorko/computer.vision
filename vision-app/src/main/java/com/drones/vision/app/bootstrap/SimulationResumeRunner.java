package com.drones.vision.app.bootstrap;

import com.drones.vision.application.simulation.SimulationService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.util.Objects;

/**
 * Calls {@link SimulationService#resumeAll()} once, at boot, restarting the TX feed for every
 * persisted simulated asset whose {@code rtsp} video device is one of this app's own TX-fed
 * simulation feeds (see that method's own javadoc for the full "frozen video after a restart"
 * story this closes).
 *
 * <p>{@code enabled} is resolved once, at construction, from {@code
 * VisionPersistenceProperties#enabled() && VisionSimulationProperties#resumeOnBoot()} (see {@link
 * WiringConfiguration#simulationResumeRunner}) — a plain boolean rather than re-reading either
 * property here, so this class stays a trivial, directly-unit-testable trigger with no property
 * binding of its own. Running with persistence disabled would be harmless (there is nothing to
 * resume — {@code feedByAsset}/the in-memory repositories were both wiped at the same restart) but
 * pointless, so it is skipped rather than run for nothing.
 *
 * <p>{@link SimulationService#resumeAll()} itself logs what it resumed/skipped and why (per-asset,
 * plus a one-line summary) — this class does not duplicate that logging, only decides whether to
 * call it at all.
 */
public final class SimulationResumeRunner implements ApplicationRunner {

    private final SimulationService simulationService;
    private final boolean enabled;

    public SimulationResumeRunner(SimulationService simulationService, boolean enabled) {
        this.simulationService = Objects.requireNonNull(simulationService, "simulationService must not be null");
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (enabled) {
            simulationService.resumeAll();
        }
    }
}

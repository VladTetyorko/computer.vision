package com.drones.vision.app;

import com.drones.vision.api.controller.OnboardingController;
import com.drones.vision.api.controller.ReadinessController;
import com.drones.vision.api.support.RemediationOrchestrator;
import com.drones.vision.app.devsupport.NoopVehicleConfigPort;
import com.drones.vision.flight.application.ReadinessService;
import com.drones.vision.flight.application.RemediationService;
import com.drones.vision.flight.application.VehicleProfileService;
import com.drones.vision.flight.domain.port.VehicleConfigPort;
import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Context test proving {@code OnboardingWiringConfiguration}'s guardrail (D17): with {@code
 * vision.onboarding.probe.enabled} at its default ({@code false}, not set here), the whole
 * onboarding pipeline is wired (every bean exists, so the driving REST adapters mount cleanly) but
 * {@link VehicleConfigPort} resolves to {@link NoopVehicleConfigPort}, refusing every probe with
 * the exact §8.1-frozen 409 body — proof the flag-off state is byte-identical to "unwired" from the
 * caller's point of view.
 *
 * <p>{@code vision.publish.enabled=false} only — deliberately the same {@code @SpringBootTest}
 * configuration shape {@link com.drones.vision.app.PersistenceWiringTest}/{@link
 * com.drones.vision.app.CvWiringTest}/over a dozen other classes already use, so this class
 * reuses their cached context instead of forcing a new one ({@code
 * com.drones.vision.app.testsupport.PostgresContextCustomizerFactory}'s own javadoc: each distinct
 * {@code @SpringBootTest} properties combination parks its own pooled connections against the
 * shared Testcontainers Postgres, and the container's {@code max_connections=100} caps how many
 * distinct combinations the whole module can afford).
 */
@SpringBootTest(properties = "vision.publish.enabled=false")
class OnboardingWiringTest {

    @Autowired
    private VehicleConfigPort vehicleConfigPort;

    @Autowired
    private VehicleProfileService vehicleProfileService;

    @Autowired
    private RemediationService remediationService;

    @Autowired
    private ReadinessService readinessService;

    @Autowired
    private RemediationOrchestrator remediationOrchestrator;

    @Autowired
    private OnboardingController onboardingController;

    @Autowired
    private ReadinessController readinessController;

    @Test
    void defaultConfigurationWiresTheNoopVehicleConfigPort() {
        assertInstanceOf(NoopVehicleConfigPort.class, vehicleConfigPort);
    }

    @Test
    void everyApplicationServiceAndDrivingAdapterIsWiredRegardlessOfTheFlag() {
        assertNotNull(vehicleProfileService);
        assertNotNull(remediationService);
        assertNotNull(readinessService);
        assertNotNull(remediationOrchestrator);
        assertNotNull(onboardingController);
        assertNotNull(readinessController);
    }

    @Test
    void probingRefusesWithTheFrozenDisabledMessageWhenTheFlagIsAtItsDefault() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> vehicleProfileService.probeCandidate("udp://0.0.0.0:14550#7", Duration.ofSeconds(10),
                        UserId.random()));

        assertEquals("vehicle probing is disabled (vision.onboarding.probe.enabled)", thrown.getMessage(),
                "must match §8.1's frozen 409 body verbatim");
    }
}

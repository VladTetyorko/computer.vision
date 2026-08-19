package com.drones.vision.app;

import com.drones.vision.adapter.mavlink.MavlinkVehicleConfigurator;
import com.drones.vision.app.devsupport.NoopVehicleConfigPort;
import com.drones.vision.flight.application.VehicleProfileService;
import com.drones.vision.flight.domain.port.VehicleConfigPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The mirror of {@link OnboardingWiringTest}: with {@code vision.onboarding.probe.enabled=true} the
 * application must <em>start</em>, and {@link VehicleConfigPort} must resolve to O4's real
 * {@link MavlinkVehicleConfigurator} rather than {@link NoopVehicleConfigPort}.
 *
 * <p>This exists because for a while it did neither. Wave O5 could not construct the configurator
 * from its own file scope, so the {@code true} branch had no bean at all and flipping the flag broke
 * startup — an operator reading the yaml would have had no way to tell. A context test is the only
 * thing that catches a conditional bean that is simply absent; every unit test passes without it.
 */
@SpringBootTest(properties = {"vision.publish.enabled=false", "vision.onboarding.probe.enabled=true"})
class OnboardingProbeEnabledWiringTest {

    @Autowired
    private VehicleConfigPort vehicleConfigPort;

    @Autowired
    private VehicleProfileService vehicleProfileService;

    @Test
    void theFlagOnPathStartsAndWiresTheRealConfigurator() {
        assertInstanceOf(MavlinkVehicleConfigurator.class, vehicleConfigPort,
                "flag on must mean the aircraft is actually talked to");
    }

    @Test
    void theNoopIsNotAlsoPresentToBeSilentlyPreferred() {
        assertFalse(vehicleConfigPort instanceof NoopVehicleConfigPort);
        assertNotNull(vehicleProfileService, "the services that consume the port still resolve");
    }
}

package com.drones.vision.app.config.wiring;

import com.drones.vision.api.support.AuxFunctionCatalog;
import com.drones.vision.app.config.properties.VisionControlProperties;
import com.drones.vision.flight.application.ControlProfileService;
import com.drones.vision.flight.application.DefaultControlProfileService;
import com.drones.vision.flight.domain.port.ControlProfileRepositoryPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

/**
 * Plain unit test (no Spring context, no Docker) for {@link ControlProfileWiring}'s bean methods —
 * docs/plans/active/CONTROLLER-SETUP-CONTEXT.md wave C6, the same shape as {@link
 * PersistenceWiringConfigurationTest}.
 */
class ControlProfileWiringTest {

    private final ControlProfileWiring wiring = new ControlProfileWiring();

    @Test
    void controlProfileServiceIsTheDefaultImplementationOverTheInjectedPort() {
        ControlProfileService service =
                wiring.controlProfileService(mock(ControlProfileRepositoryPort.class));

        assertInstanceOf(DefaultControlProfileService.class, service);
    }

    @Test
    void anUnconfiguredDeploymentGetsTheBuiltInAuxFunctionMenu() {
        AuxFunctionCatalog catalog = wiring.auxFunctionCatalog(new VisionControlProperties(List.of()));

        assertEquals(AuxFunctionCatalog.defaults(), catalog);
        assertFalse(catalog.functions().isEmpty());
    }

    /**
     * A configured menu <em>replaces</em> the built-in one rather than extending it: a deployment
     * that lists three options wants three, and silently appending thirteen more would make the
     * property look ignored.
     */
    @Test
    void aConfiguredMenuReplacesTheBuiltInOneEntirely() {
        AuxFunctionCatalog catalog = wiring.auxFunctionCatalog(new VisionControlProperties(List.of(
                new VisionControlProperties.AuxFunction(300, "Airframe-specific option"))));

        assertEquals(1, catalog.functions().size());
        assertEquals(300, catalog.functions().get(0).number());
        assertEquals("Airframe-specific option", catalog.functions().get(0).label());
    }

    /** {@code null} from a half-written yaml block must not become a null list downstream. */
    @Test
    void aMissingAuxFunctionListBindsAsEmptyRatherThanNull() {
        assertEquals(AuxFunctionCatalog.defaults(), wiring.auxFunctionCatalog(new VisionControlProperties(null)));
    }
}

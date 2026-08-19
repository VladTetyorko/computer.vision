package com.drones.vision.app;

import com.drones.vision.api.controller.CameraPoseController;
import com.drones.vision.api.controller.MapTracksController;
import com.drones.vision.api.support.FixedCameraGeoProperties;
import com.drones.vision.app.geo.TrackProjectionRunner;
import com.drones.vision.map.application.track.CameraPoseService;
import com.drones.vision.map.application.track.TrackProjectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Context test proving {@code FixedCameraGeoWiringConfiguration}'s guardrail (D8): with {@code
 * vision.geo.fixed-camera.enabled} at its default ({@code false}, not set here), the whole feature
 * is wired (every application service exists, so {@link CameraPoseController}/{@link
 * MapTracksController} mount cleanly) but {@link TrackProjectionRunner} is entirely absent — no
 * scheduled tick, no background work — and every endpoint's own flag check refuses with the frozen
 * §5 {@code 409} body, proof the flag-off state is byte-identical to "unwired" from the caller's
 * point of view.
 *
 * <p>{@code vision.publish.enabled=false} only — deliberately the same {@code @SpringBootTest}
 * configuration shape {@link OnboardingWiringTest}/{@link PersistenceWiringTest} already use, so
 * this class reuses their cached context instead of forcing a new one (see {@link
 * DetectionDemandDisabledWiringTest}'s own javadoc for why the connection-pool budget makes this
 * matter).
 */
@SpringBootTest(properties = "vision.publish.enabled=false")
class FixedCameraGeoWiringTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private CameraPoseController cameraPoseController;

    @Autowired
    private MapTracksController mapTracksController;

    @Autowired
    private CameraPoseService cameraPoseService;

    @Autowired
    private TrackProjectionService trackProjectionService;

    @Autowired
    private FixedCameraGeoProperties fixedCameraGeoApiProperties;

    @Test
    void defaultConfigurationBuildsNoTrackProjectionRunner() {
        assertTrue(applicationContext.getBeansOfType(TrackProjectionRunner.class).isEmpty());
    }

    @Test
    void everyApplicationServiceAndDrivingAdapterIsWiredRegardlessOfTheFlag() {
        assertNotNull(cameraPoseController);
        assertNotNull(mapTracksController);
        assertNotNull(cameraPoseService);
        assertNotNull(trackProjectionService);
    }

    @Test
    void theApiPropertiesBridgeReportsDisabled() {
        assertFalse(fixedCameraGeoApiProperties.enabled());
    }

    @Test
    void requireEnabledRefusesWithTheFrozenDisabledMessageWhenTheFlagIsAtItsDefault() {
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, fixedCameraGeoApiProperties::requireEnabled);

        assertEquals("fixed-camera geolocation is disabled (vision.geo.fixed-camera.enabled)", thrown.getMessage(),
                "must match §5's frozen 409 body verbatim");
    }
}

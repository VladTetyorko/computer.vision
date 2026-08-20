package com.drones.vision.app;

import com.drones.vision.api.controller.GeoCorrectionController;
import com.drones.vision.api.controller.GeoRegionController;
import com.drones.vision.api.support.VisualGeoProperties;
import com.drones.vision.app.geo.VisualGeoRunner;
import com.drones.vision.flight.application.TrackCorrectionService;
import com.drones.vision.perception.application.geo.GeolocationSessionService;
import com.drones.vision.perception.application.geo.ReferenceRegionService;
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
 * Context test proving {@code VisualGeoWiringConfiguration}'s D9 guardrail: with {@code
 * vision.geo.visual.enabled} at its default ({@code false}, not set here), the whole feature is
 * wired (every application service exists, so {@link GeoRegionController}/{@link
 * GeoCorrectionController} mount cleanly) but {@link VisualGeoRunner} is entirely absent — no
 * scheduled tick, no gRPC geo session ever opened — and the vision-api edge's own {@link
 * VisualGeoProperties#requireEnabled()} refuses with the frozen §3.3/D9 {@code 409} body, proof the
 * flag-off state is byte-identical to "unwired" from the caller's point of view (docs/plans/active/
 * VISUAL-GEO-V2-PLAN.md D9/H5's own "asserted explicitly in H5" requirement).
 *
 * <p>{@code vision.publish.enabled=false} only — the same {@code @SpringBootTest} configuration
 * shape {@link FixedCameraGeoWiringTest}/{@link OnboardingWiringTest}/{@link PersistenceWiringTest}
 * already use, so this class reuses their cached context instead of forcing a new one.
 */
@SpringBootTest(properties = "vision.publish.enabled=false")
class VisualGeoWiringTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private GeoRegionController geoRegionController;

    @Autowired
    private GeoCorrectionController geoCorrectionController;

    @Autowired
    private ReferenceRegionService referenceRegionService;

    @Autowired
    private GeolocationSessionService geolocationSessionService;

    @Autowired
    private TrackCorrectionService trackCorrectionService;

    @Autowired
    private VisualGeoProperties visualGeoApiProperties;

    @Test
    void defaultConfigurationBuildsNoVisualGeoRunner() {
        assertTrue(applicationContext.getBeansOfType(VisualGeoRunner.class).isEmpty());
    }

    @Test
    void everyApplicationServiceAndDrivingAdapterIsWiredRegardlessOfTheFlag() {
        assertNotNull(geoRegionController);
        assertNotNull(geoCorrectionController);
        assertNotNull(referenceRegionService);
        assertNotNull(geolocationSessionService);
        assertNotNull(trackCorrectionService);
    }

    @Test
    void theApiPropertiesBridgeReportsDisabled() {
        assertFalse(visualGeoApiProperties.enabled());
    }

    @Test
    void requireEnabledRefusesWithTheFrozenDisabledMessageWhenTheFlagIsAtItsDefault() {
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, visualGeoApiProperties::requireEnabled);

        assertEquals("visual geolocation is disabled (vision.geo.visual.enabled)", thrown.getMessage(),
                "must match D9's frozen 409 body verbatim");
    }
}

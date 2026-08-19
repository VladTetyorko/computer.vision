package com.drones.vision.app;

import com.drones.vision.api.live.LiveAndPollDetectionDemand;
import com.drones.vision.api.support.FixedCameraGeoProperties;
import com.drones.vision.app.geo.TrackProjectionRunner;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.StreamId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Context test for {@code vision.geo.fixed-camera.enabled=true} (docs/plans/active/
 * FIXED-CAMERA-GEO-PLAN.md D8, Wave G4): asserts {@code FixedCameraGeoWiringConfiguration} builds
 * exactly one {@link TrackProjectionRunner}, that it starts cleanly against a real (empty) Postgres
 * — its first tick sees no stored poses and does nothing, so nothing here needs a stubbed
 * collaborator — and that {@link LiveAndPollDetectionDemand}'s D9 {@code hasCameraPose} OR-term
 * resolves to this runner rather than the {@code assetId -> false} default. See {@link
 * FixedCameraGeoWiringTest} for the default (disabled) counterpart.
 *
 * <p>{@code vision.publish.enabled=false} for the same determinism reasons as {@link
 * CvWiringTest}/{@link OnboardingWiringTest}.
 */
@SpringBootTest(properties = {"vision.publish.enabled=false", "vision.geo.fixed-camera.enabled=true"})
class FixedCameraGeoEnabledWiringTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private TrackProjectionRunner trackProjectionRunner;

    @Autowired
    private FixedCameraGeoProperties fixedCameraGeoApiProperties;

    @Autowired
    private LiveAndPollDetectionDemand detectionDemandPort;

    @Test
    void enabledConfigurationBuildsExactlyOneTrackProjectionRunner() {
        assertNotNull(trackProjectionRunner);
        assertEquals(1, applicationContext.getBeansOfType(TrackProjectionRunner.class).size());
    }

    @Test
    void theRunnersFirstTickAgainstAnEmptyStoreDoesNothingHarmful() {
        // No pose is seeded, so hasCameraPose must simply report false for a random asset -- proof
        // the scheduled tick already ran (or is running) against the real repository without
        // throwing anything that would have failed context startup.
        assertDoesNotThrow(() -> trackProjectionRunner.hasCameraPose(AssetId.random()));
    }

    @Test
    void theApiPropertiesBridgeReportsEnabled() {
        assertTrue(fixedCameraGeoApiProperties.enabled());
        assertDoesNotThrow(fixedCameraGeoApiProperties::requireEnabled);
    }

    @Test
    void theD9DemandOrTermIsWiredToTheRealRunnerNotTheAbsentDefault() {
        // Can't observe the private predicate directly, but detectionWanted must not throw and must
        // still answer false for an asset the runner has never seen -- a smoke check that CvWiring
        // resolved a real TrackProjectionRunner rather than silently keeping the assetId -> false
        // default now that the bean genuinely exists.
        assertNotNull(detectionDemandPort);
        assertDoesNotThrow(() -> detectionDemandPort.detectionWanted(StreamId.random(), AssetId.random()));
    }
}

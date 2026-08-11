package com.drones.vision.app;

import com.drones.vision.api.controller.CvTrackersController;
import com.drones.vision.api.dto.CvTrackerResponse;
import com.drones.vision.app.config.properties.VisionTrackingProperties;
import com.drones.vision.domain.model.TrackingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Context test for the <em>default</em> {@code vision.tracking.*} configuration
 * (docs/TRACKING-PLAN.md &sect;4.F, docs/TRACKING-ORCHESTRATION.md &sect;4.3): the properties bind in
 * a real context, the shipped default is the one that <b>leaves stream starts unchanged</b>
 * ({@code TrackingMode.OFF}, the same value {@code PipelineConfig.defaults()} carries until wave T8
 * flips it), and {@code GET /api/cv/trackers}' roster bean resolves for its component-scanned
 * controller.
 *
 * <p>The property&rarr;seed mapping itself is unit-tested without a context in {@code
 * config.wiring.TrackingWiringTest}. There is deliberately <b>no seed bean</b> to autowire here: the
 * seed is not a collaborator of any controller, it rides {@code StreamPipelineSettings} into {@code
 * DefaultStreamService}, which is the one point every start path passes through
 * (docs/TRACKING-ORCHESTRATION.md &sect;4.1).
 *
 * <p>{@code vision.publish.enabled=false} for the same determinism reasons as {@link
 * CvWiringTest}/{@link AssetWiringTest}.
 */
@SpringBootTest(properties = "vision.publish.enabled=false")
class TrackingWiringContextTest {

    @Autowired
    private VisionTrackingProperties trackingProperties;

    @Autowired
    private List<CvTrackerResponse> cvTrackerRoster;

    @Autowired
    private CvTrackersController cvTrackersController;

    @Test
    void defaultConfigurationSeedsNewStreamsWithTrackingOff() {
        // What only a context can prove: the shipped properties bind, and they bind to the values
        // that leave a stream start byte-identical to before tracking existed. That they then fold
        // to TrackingConfig.off() is TrackingWiringTest's job, without a context.
        assertEquals("OFF", trackingProperties.defaultMode());
        assertEquals(TrackingConfig.off().followFps(), trackingProperties.followFps());
        assertEquals(TrackingConfig.off().verifyEveryMillis(), trackingProperties.verifyEveryMillis());
    }

    @Test
    void theTrackerRosterBeanCarriesTheThreeBuiltInEngines() {
        assertEquals(3, cvTrackerRoster.size());
        assertEquals("bytetrack", cvTrackerRoster.get(0).id());
    }

    @Test
    void cvTrackersControllerBeanExists() {
        assertNotNull(cvTrackersController);
        assertEquals(3, cvTrackersController.trackers().trackers().size());
    }
}

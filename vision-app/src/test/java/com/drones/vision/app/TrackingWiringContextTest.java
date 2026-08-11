package com.drones.vision.app;

import com.drones.vision.api.controller.CvTrackersController;
import com.drones.vision.api.dto.CvTrackerResponse;
import com.drones.vision.domain.model.TrackingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Context test for the <em>default</em> {@code vision.tracking.*} configuration
 * (docs/TRACKING-PLAN.md &sect;4.F, docs/TRACKING-ORCHESTRATION.md &sect;4.3): the two beans {@code
 * vision-api}'s component-scanned controllers need actually resolve, and the shipped default is the
 * one that <b>leaves stream starts unchanged</b> — {@code TrackingMode.OFF}, the same value {@code
 * PipelineConfig.defaults()} carries until wave T8 flips it.
 *
 * <p>The mapping itself is unit-tested without a context in {@code
 * config.wiring.TrackingWiringTest}; this class exists for what only a real context can prove — that
 * the {@code TrackingConfig} bean is unambiguous enough for {@code StreamController}/{@code
 * AssetController} to autowire, and that {@code GET /api/cv/trackers}' controller has its roster.
 *
 * <p>{@code vision.publish.enabled=false} for the same determinism reasons as {@link
 * CvWiringTest}/{@link AssetWiringTest}.
 */
@SpringBootTest(properties = "vision.publish.enabled=false")
class TrackingWiringContextTest {

    @Autowired
    private TrackingConfig streamStartTrackingDefaults;

    @Autowired
    private List<CvTrackerResponse> cvTrackerRoster;

    @Autowired
    private CvTrackersController cvTrackersController;

    @Test
    void defaultConfigurationSeedsNewStreamsWithTrackingOff() {
        assertEquals(TrackingConfig.off(), streamStartTrackingDefaults);
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

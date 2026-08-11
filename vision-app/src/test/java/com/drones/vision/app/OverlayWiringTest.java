package com.drones.vision.app;

import com.drones.vision.adapter.overlay.Java2DOverlayRenderer;
import com.drones.vision.perception.domain.port.OverlayPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Context test for docs/plans/done/MVP1-PLAN.md §C8 bullet 3's wiring half: the {@link OverlayPort} bean
 * exists and is a real {@link Java2DOverlayRenderer} — the collaborator {@link
 * WiringConfiguration#streamService} threads into every {@code StreamPipeline} it starts (see
 * {@code com.drones.vision.perception.application.pipeline.StreamPipeline}'s own overlay burn-in seam, and
 * adapter-overlay/MODULE.md for the renderer's pass-through-never-throws rules).
 *
 * <p>{@code vision.publish.enabled=false} for determinism, matching {@link
 * AssetWiringTest}/{@link CvWiringTest}'s style — this test doesn't care about stream egress.
 */
@SpringBootTest(properties = "vision.publish.enabled=false")
class OverlayWiringTest {

    @Autowired
    private OverlayPort overlayPort;

    @Test
    void overlayPortResolvesToJava2DOverlayRenderer() {
        assertInstanceOf(Java2DOverlayRenderer.class, overlayPort);
    }
}

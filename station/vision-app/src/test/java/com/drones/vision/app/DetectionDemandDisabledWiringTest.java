package com.drones.vision.app;

import com.drones.vision.api.live.LiveAndPollDetectionDemand;
import com.drones.vision.api.support.StreamDetectionSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Context test for {@code vision.cv.demand.enabled=false} (docs/plans/active/CV-DEMAND-PLAN.md
 * §3.7/§5, wiring item 5): asserts the context still loads cleanly with {@link
 * LiveAndPollDetectionDemand} entirely absent — not merely inactive — and {@link
 * StreamDetectionSupport#demand()} therefore {@code null}, which its own {@code touched} makes a
 * no-op. This is the escape hatch a deployment reaches for to keep unattended detection running
 * with nobody watching; every stream's {@code detectionDemand} then stays fail-open {@code true}
 * (D1's default), exactly as it behaved before this plan existed.
 *
 * <p>Looks up {@link LiveAndPollDetectionDemand} via {@link ApplicationContext#getBeansOfType}
 * rather than {@code @Autowired}, mirroring {@link LiveDisabledWiringTest}'s own reasoning: a plain
 * {@code @Autowired} field is required by default and would fail the context entirely if the bean
 * is genuinely absent, defeating the point of this test.
 *
 * <p>{@code vision.publish.enabled=false}/{@code vision.live.enabled=false} for the same
 * determinism reasons as {@link CvWiringTest}.
 */
@SpringBootTest(properties = {"vision.cv.demand.enabled=false", "vision.publish.enabled=false",
        "vision.live.enabled=false"})
class DetectionDemandDisabledWiringTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private StreamDetectionSupport streamDetectionSupport;

    @Test
    void noDetectionDemandPortBeanExistsWhenDisabled() {
        assertTrue(applicationContext.getBeansOfType(LiveAndPollDetectionDemand.class).isEmpty());
    }

    @Test
    void streamDetectionSupportHasNoDemandToTouchWhenDisabled() {
        assertNull(streamDetectionSupport.demand());
    }
}

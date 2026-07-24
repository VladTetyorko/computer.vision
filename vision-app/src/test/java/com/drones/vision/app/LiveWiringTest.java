package com.drones.vision.app;

import com.drones.vision.api.LiveController;
import com.drones.vision.api.live.LiveUpdateRegistry;
import com.drones.vision.domain.port.out.AuditTrailPort;
import com.drones.vision.domain.port.out.EventPublisherPort;
import com.drones.vision.domain.port.out.LiveUpdatePublisherPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Context test for the <em>default</em> {@code vision.live.*} configuration (no override, per
 * {@link VisionLiveProperties#enabled()}'s default of {@code true} — docs/REALTIME-PLAN.md §4,
 * item 4): asserts {@link WiringConfiguration} wires the real {@link LiveUpdateRegistry} (not the
 * no-op fallback), that {@code GET /api/live}'s {@link LiveController} bean exists, and that both
 * {@link EventPublisherPort}/{@link AuditTrailPort} are wrapped in their respective live-update
 * decorators — see {@link LiveDisabledWiringTest} for the opposite (disabled) counterpart.
 *
 * <p>{@code vision.publish.enabled=false} for the same determinism reasons as {@link
 * CvWiringTest}/{@link AssetWiringTest}.
 */
@SpringBootTest(properties = "vision.publish.enabled=false")
class LiveWiringTest {

    @Autowired
    private LiveUpdatePublisherPort liveUpdatePublisherPort;

    @Autowired
    private LiveController liveController;

    @Autowired
    private EventPublisherPort eventPublisherPort;

    @Autowired
    private AuditTrailPort auditTrailPort;

    @Test
    void defaultConfigurationWiresTheRealLiveUpdateRegistry() {
        assertInstanceOf(LiveUpdateRegistry.class, liveUpdatePublisherPort);
    }

    @Test
    void liveControllerBeanExists() {
        assertNotNull(liveController);
    }

    @Test
    void eventPublisherIsWrappedWithTheLiveUpdateDecorator() {
        assertInstanceOf(LiveUpdateEventPublisher.class, eventPublisherPort);
    }

    @Test
    void auditTrailIsWrappedWithTheLiveUpdateDecorator() {
        assertInstanceOf(LiveUpdateAuditTrail.class, auditTrailPort);
    }
}

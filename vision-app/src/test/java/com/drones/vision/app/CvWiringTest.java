package com.drones.vision.app;

import com.drones.vision.app.devsupport.LoggingEventPublisher;
import com.drones.vision.app.devsupport.NoopDetectionPort;
import com.drones.vision.domain.port.out.DetectionPort;
import com.drones.vision.domain.port.out.EventPublisherPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Context test for the <em>default</em> {@code vision.cv.*} configuration (no override, per
 * {@link VisionCvProperties#enabled()}'s default of {@code false}): asserts {@link
 * WiringConfiguration} keeps today's behavior — {@link DetectionPort} stays the devsupport {@link
 * NoopDetectionPort}, and {@link EventPublisherPort} stays the plain {@link
 * LoggingEventPublisher} rather than being wrapped by {@link
 * DetectionSessionCleanupEventPublisher} (which only ever wraps it when CV is enabled — see
 * {@link CvEnabledWiringTest}).
 *
 * <p>{@code vision.publish.enabled=false} for the same determinism reasons as {@link
 * DiscoveryWiringTest}/{@link AssetWiringTest} — this test doesn't care about stream egress.
 * {@code vision.live.enabled=false} (docs/REALTIME-PLAN.md §4) isolates this test from the
 * server-push feature's own {@code EventPublisherPort} decorator ({@link LiveUpdateEventPublisher}
 * — see {@link LiveWiringTest}/{@link LiveDisabledWiringTest} for that feature's own coverage), so
 * this class's assertions stay about CV wiring specifically, not about which other decorators
 * happen to also be layered on by default.
 */
@SpringBootTest(properties = {"vision.publish.enabled=false", "vision.live.enabled=false"})
class CvWiringTest {

    @Autowired
    private DetectionPort detectionPort;

    @Autowired
    private EventPublisherPort eventPublisherPort;

    @Test
    void defaultConfigurationKeepsNoopDetectionPort() {
        assertInstanceOf(NoopDetectionPort.class, detectionPort);
    }

    @Test
    void defaultConfigurationKeepsPlainLoggingEventPublisher() {
        assertInstanceOf(LoggingEventPublisher.class, eventPublisherPort);
    }
}

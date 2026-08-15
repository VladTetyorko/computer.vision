package com.drones.vision.app;

import com.drones.vision.api.controller.LiveController;
import com.drones.vision.api.live.LiveUpdateRegistry;
import com.drones.vision.app.devsupport.InMemoryAuditTrail;
import com.drones.vision.app.devsupport.InMemoryDetectionEventRepository;
import com.drones.vision.app.devsupport.LoggingEventPublisher;
import com.drones.vision.app.devsupport.NoopLiveUpdatePublisher;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.perception.domain.port.DetectionEventRepositoryPort;
import com.drones.vision.perception.domain.port.DetectionLiveUpdatePort;
import com.drones.vision.platform.EventLiveUpdatePort;
import com.drones.vision.platform.EventPublisherPort;
import com.drones.vision.map.domain.port.MapLiveUpdatePort;
import com.drones.vision.flight.domain.port.TelemetryLiveUpdatePort;
import com.drones.vision.warehouse.domain.port.FleetLiveUpdatePort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Context test for {@code vision.live.enabled=false} (docs/plans/done/REALTIME-PLAN.md §4, item 4): asserts
 * the context still loads cleanly but with every one of the five live-update ports ({@link
 * FleetLiveUpdatePort}, {@link TelemetryLiveUpdatePort}, {@link DetectionLiveUpdatePort}, {@link
 * MapLiveUpdatePort}, {@link EventLiveUpdatePort} — the ports the former god-port {@code
 * LiveUpdatePublisherPort} split into, docs/plans/active/DOMAIN-SEPARATION-W1.md §15, W1.6b) falling
 * back to {@link NoopLiveUpdatePublisher}, {@code GET /api/live}'s {@link LiveController}/{@link
 * LiveUpdateRegistry} beans entirely absent (so the endpoint 404s, same as any other unmapped
 * route), and neither {@link EventPublisherPort} nor {@link AuditTrailPort} wrapped in their
 * live-update decorators — see {@link LiveWiringTest} for the opposite (enabled/default)
 * counterpart.
 *
 * <p>Looks up {@link LiveController}/{@link LiveUpdateRegistry} via {@link
 * ApplicationContext#getBeansOfType} rather than {@code @Autowired}, mirroring {@link
 * DiscoveryDisabledWiringTest}'s own reasoning: a plain {@code @Autowired} field is required by
 * default and would fail the context entirely if the bean is genuinely absent, defeating the
 * point of this test.
 *
 * <p>{@code vision.publish.enabled=false} for the same determinism reasons as {@link
 * CvWiringTest}/{@link AssetWiringTest}.
 */
@SpringBootTest(properties = {"vision.live.enabled=false", "vision.publish.enabled=false"})
class LiveDisabledWiringTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private FleetLiveUpdatePort fleetLiveUpdatePort;

    @Autowired
    private TelemetryLiveUpdatePort telemetryLiveUpdatePort;

    @Autowired
    private DetectionLiveUpdatePort detectionLiveUpdatePort;

    @Autowired
    private MapLiveUpdatePort mapLiveUpdatePort;

    @Autowired
    private EventLiveUpdatePort eventLiveUpdatePort;

    @Autowired
    private EventPublisherPort eventPublisherPort;

    @Autowired
    private AuditTrailPort auditTrailPort;

    @Autowired
    private DetectionEventRepositoryPort detectionEventRepositoryPort;

    @Test
    void disabledConfigurationFallsBackToTheNoopLiveUpdatePublisherForEveryPort() {
        assertInstanceOf(NoopLiveUpdatePublisher.class, fleetLiveUpdatePort);
        assertInstanceOf(NoopLiveUpdatePublisher.class, telemetryLiveUpdatePort);
        assertInstanceOf(NoopLiveUpdatePublisher.class, detectionLiveUpdatePort);
        assertInstanceOf(NoopLiveUpdatePublisher.class, mapLiveUpdatePort);
        assertInstanceOf(NoopLiveUpdatePublisher.class, eventLiveUpdatePort);
    }

    @Test
    void noLiveControllerOrRegistryBeanExistsWhenDisabled() {
        assertTrue(applicationContext.getBeansOfType(LiveController.class).isEmpty());
        assertTrue(applicationContext.getBeansOfType(LiveUpdateRegistry.class).isEmpty());
    }

    @Test
    void eventPublisherIsNotWrappedWhenDisabled() {
        assertInstanceOf(LoggingEventPublisher.class, eventPublisherPort);
    }

    @Test
    void auditTrailIsNotWrappedWhenDisabled() {
        assertInstanceOf(InMemoryAuditTrail.class, auditTrailPort);
    }

    @Test
    void detectionEventRepositoryIsNotWrappedWhenDisabled() {
        assertInstanceOf(InMemoryDetectionEventRepository.class, detectionEventRepositoryPort);
    }
}

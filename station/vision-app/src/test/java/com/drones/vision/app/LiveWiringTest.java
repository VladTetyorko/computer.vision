package com.drones.vision.app;

import com.drones.vision.api.controller.LiveController;
import com.drones.vision.api.live.LiveUpdateRegistry;
import com.drones.vision.api.live.SystemStatusSampler;
import com.drones.vision.app.events.LiveUpdateAuditTrail;
import com.drones.vision.app.events.LiveUpdateDetectionEventRepository;
import com.drones.vision.app.events.LiveUpdateEventPublisher;
import com.drones.vision.flight.domain.port.GeofenceLiveUpdatePort;
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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Context test for the <em>default</em> {@code vision.live.*} configuration (no override, per
 * {@link VisionLiveProperties#enabled()}'s default of {@code true} — docs/plans/done/REALTIME-PLAN.md §4,
 * item 4): asserts {@link ApplicationServiceWiring} wires the real {@link LiveUpdateRegistry} (not
 * the no-op fallback) behind every one of the seven live-update ports ({@link FleetLiveUpdatePort},
 * {@link TelemetryLiveUpdatePort}, {@link DetectionLiveUpdatePort}, {@link MapLiveUpdatePort},
 * {@link EventLiveUpdatePort} — the ports the former god-port {@code LiveUpdatePublisherPort} split
 * into, docs/plans/active/DOMAIN-SEPARATION-W1.md §15, W1.6b, plus {@link GeofenceLiveUpdatePort}
 * added for the {@code zones} topic, docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md &sect;3
 * D2/&sect;4.1, wave L3), that {@code GET /api/live}'s {@link LiveController} bean exists, that
 * {@link SystemStatusSampler} is present and actively sampling (wave L4), and that {@link
 * EventPublisherPort}/{@link AuditTrailPort}/{@link DetectionEventRepositoryPort} are each wrapped in
 * their respective live-update decorators — see {@link LiveDisabledWiringTest} for the opposite
 * (disabled) counterpart.
 *
 * <p>{@code vision.publish.enabled=false} for the same determinism reasons as {@link
 * CvWiringTest}/{@link AssetWiringTest}.
 */
@SpringBootTest(properties = "vision.publish.enabled=false")
class LiveWiringTest {

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
    private GeofenceLiveUpdatePort geofenceLiveUpdatePort;

    @Autowired
    private LiveController liveController;

    @Autowired
    private SystemStatusSampler systemStatusSampler;

    @Autowired
    private EventPublisherPort eventPublisherPort;

    @Autowired
    private AuditTrailPort auditTrailPort;

    @Autowired
    private DetectionEventRepositoryPort detectionEventRepositoryPort;

    @Test
    void defaultConfigurationWiresTheRealLiveUpdateRegistryForEveryPort() {
        assertInstanceOf(LiveUpdateRegistry.class, fleetLiveUpdatePort);
        assertInstanceOf(LiveUpdateRegistry.class, telemetryLiveUpdatePort);
        assertInstanceOf(LiveUpdateRegistry.class, detectionLiveUpdatePort);
        assertInstanceOf(LiveUpdateRegistry.class, mapLiveUpdatePort);
        assertInstanceOf(LiveUpdateRegistry.class, eventLiveUpdatePort);
        assertInstanceOf(LiveUpdateRegistry.class, geofenceLiveUpdatePort);
    }

    @Test
    void liveControllerBeanExists() {
        assertNotNull(liveController);
    }

    @Test
    void systemStatusSamplerBeanExists() {
        assertNotNull(systemStatusSampler);
    }

    @Test
    void eventPublisherIsWrappedWithTheLiveUpdateDecorator() {
        assertInstanceOf(LiveUpdateEventPublisher.class, eventPublisherPort);
    }

    @Test
    void auditTrailIsWrappedWithTheLiveUpdateDecorator() {
        assertInstanceOf(LiveUpdateAuditTrail.class, auditTrailPort);
    }

    @Test
    void detectionEventRepositoryIsWrappedWithTheLiveUpdateDecorator() {
        assertInstanceOf(LiveUpdateDetectionEventRepository.class, detectionEventRepositoryPort);
    }
}

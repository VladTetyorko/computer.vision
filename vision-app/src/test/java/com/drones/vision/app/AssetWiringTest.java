package com.drones.vision.app;

import com.drones.vision.adapter.mavlink.MavlinkFeedTransmitter;
import com.drones.vision.adapter.mavlink.MavlinkTelemetrySource;
import com.drones.vision.adapter.mjpeg.MjpegFeedTransmitter;
import com.drones.vision.adapter.rtsp.RtspFeedTransmitter;
import com.drones.vision.api.EventController;
import com.drones.vision.api.FleetController;
import com.drones.vision.api.SimulationController;
import com.drones.vision.application.AssetService;
import com.drones.vision.application.CategoryService;
import com.drones.vision.application.DeviceService;
import com.drones.vision.application.FeedTransmitterRegistry;
import com.drones.vision.application.FleetSummaryService;
import com.drones.vision.application.ReplayService;
import com.drones.vision.application.SimulationService;
import com.drones.vision.domain.model.FeedSpec;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.port.out.AssetRepositoryPort;
import com.drones.vision.domain.port.out.AssetUsageRepositoryPort;
import com.drones.vision.domain.port.out.AuditTrailPort;
import com.drones.vision.domain.port.out.CategoryRepositoryPort;
import com.drones.vision.domain.port.out.DetectionEventRepositoryPort;
import com.drones.vision.domain.port.out.FeedTransmitterPort;
import com.drones.vision.domain.port.out.TelemetryRepositoryPort;
import com.drones.vision.domain.port.out.TelemetrySourcePort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Context test asserting {@link WiringConfiguration} registers every asset-model bean that
 * {@code AssetController}/{@code CategoryController} (component-scanned from {@code vision-api})
 * require: the service interfaces and the driven repository ports.
 *
 * <p>This test's real assertion is largely "the context loaded" — a missing bean fails
 * {@code @SpringBootTest} itself. The explicit {@code assertNotNull} calls double as a readable
 * inventory of what the asset model needs wired, mirroring {@link PublishWiringTest}/{@link
 * DiscoveryWiringTest}'s style for other wiring concerns.
 *
 * <p>The inventory shrank when the per-operation {@code *UseCase} interfaces collapsed into one
 * service interface per area: six autowired use cases became {@link AssetService} plus
 * {@link CategoryService}, which is the whole point of that refactor.
 *
 * <p>{@code vision.publish.enabled=false} for determinism, same as {@link
 * SimStreamSmokeTest}/{@link DiscoveryWiringTest} — this test doesn't care about stream egress
 * and shouldn't depend on mediamtx being reachable.
 *
 * <p>Extended for docs/CYCLES-PLAN.md §1c: {@link SimulationService} and {@link
 * SimulationController} (the one-call, zero-hardware simulation entry point) are asserted here
 * too rather than in a new test class, since they are asset-model beans built directly on top of
 * {@link AssetService}. Further extended for docs/CYCLES-PLAN.md §3: a {@link FeedTransmitterPort}
 * (backed by {@link RtspFeedTransmitter}) was asserted to resolve directly — since docs/CYCLES-PLAN.md
 * §5 generalized {@link #simulationService}'s single transmitter dependency into a {@link
 * FeedTransmitterRegistry} (mirroring {@code VideoSourceRegistry}) once a second transmit protocol
 * ({@code mjpeg}, backed by {@link MjpegFeedTransmitter}) exists alongside {@code rtsp}, this now
 * autowires the whole {@code List<FeedTransmitterPort>} and asserts both adapters are present and
 * that the registry it feeds resolves each by protocol.
 *
 * <p>Further extended for docs/MVP2-PLAN.md R-a2: {@link ReplayService} is asserted here too
 * rather than in a new wiring-test class, for the same "small, focused, asset-model-adjacent bean"
 * reasoning as {@link SimulationService}/{@link CategoryService} above.
 *
 * <p>Further extended for docs/MVP2-PLAN.md X-a: {@link MavlinkTelemetrySource} (RX) is asserted
 * to resolve as a {@code TelemetrySourcePort} bean, and {@link MavlinkFeedTransmitter} (TX) joins
 * the {@code List<FeedTransmitterPort>}/{@link FeedTransmitterRegistry} inventory the same way
 * {@link MjpegFeedTransmitter} did in §5 — {@code MavlinkFeedTransmitter} is not yet wired into
 * {@link SimulationService}'s {@code transport} dispatch (out of X-a's scope; it is independently
 * usable via the plain device/asset APIs, see adapter-mavlink/MODULE.md), so only the registry's
 * own protocol-based resolution is asserted here, not a {@code SimulationService} round trip.
 *
 * <p>Further extended for docs/MVP2-PLAN.md E-a: {@link DetectionEventRepositoryPort} is asserted
 * here too, for the same "small, focused, asset-model-adjacent bean" reasoning as {@link
 * ReplayService} above, and {@link EventController} is asserted to resolve its constructor
 * dependency, mirroring {@link SimulationController}'s own assertion.
 *
 * <p>Further extended for docs/MVP3-PLAN.md C-a: {@link FleetSummaryService} is asserted here too,
 * for the same "small, focused, asset-model-adjacent bean" reasoning as {@link ReplayService}
 * above, and {@link FleetController} is asserted to resolve its constructor dependency, mirroring
 * {@link EventController}'s own assertion.
 */
@SpringBootTest(properties = "vision.publish.enabled=false")
class AssetWiringTest {

    @Autowired
    private AssetService assetService;

    @Autowired
    private CategoryService categoryService;

    /** docs/MVP2-PLAN.md R-a2: the bean R-a's own writeup flagged as the missing piece. */
    @Autowired
    private ReplayService replayService;

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private SimulationService simulationService;

    @Autowired
    private SimulationController simulationController;

    /** docs/MVP2-PLAN.md E-a: debounced detection events (in-memory store, unconditional). */
    @Autowired
    private DetectionEventRepositoryPort detectionEventRepositoryPort;

    /** docs/MVP2-PLAN.md E-a: {@code GET /api/events}/{@code GET /api/streams/{id}/events}. */
    @Autowired
    private EventController eventController;

    /** docs/MVP3-PLAN.md C-a: the manager dashboard's aggregated fleet read. */
    @Autowired
    private FleetSummaryService fleetSummaryService;

    /** docs/MVP3-PLAN.md C-a: {@code GET /api/fleet/summary}. */
    @Autowired
    private FleetController fleetController;

    @Autowired
    private List<FeedTransmitterPort> feedTransmitterPorts;

    @Autowired
    private FeedTransmitterRegistry feedTransmitterRegistry;

    @Autowired
    private CategoryRepositoryPort categoryRepositoryPort;

    @Autowired
    private AssetRepositoryPort assetRepositoryPort;

    @Autowired
    private AssetUsageRepositoryPort assetUsageRepositoryPort;

    @Autowired
    private TelemetryRepositoryPort telemetryRepositoryPort;

    @Autowired
    private List<TelemetrySourcePort> telemetrySourcePorts;

    @Autowired
    private AuditTrailPort auditTrailPort;

    /** The principal control-plane changes are attributed to until authentication lands. */
    @Autowired
    private Ownership actingOwnership;

    @Test
    void everyAssetModelServiceAndRepositoryBeanIsRegistered() {
        assertNotNull(assetService, "AssetService bean must be registered");
        assertNotNull(categoryService, "CategoryService bean must be registered");
        assertNotNull(replayService, "ReplayService bean must be registered (docs/MVP2-PLAN.md R-a2)");
        assertNotNull(deviceService, "DeviceService bean must be registered");
        assertNotNull(simulationService, "SimulationService bean must be registered");
        assertNotNull(simulationController, "SimulationController must resolve its constructor dependencies");
        assertNotNull(categoryRepositoryPort, "CategoryRepositoryPort bean must be registered");
        assertNotNull(assetRepositoryPort, "AssetRepositoryPort bean must be registered");
        assertNotNull(assetUsageRepositoryPort, "AssetUsageRepositoryPort bean must be registered");
        assertNotNull(telemetryRepositoryPort, "TelemetryRepositoryPort bean must be registered");
        assertNotNull(auditTrailPort, "AuditTrailPort bean must be registered");
        assertNotNull(actingOwnership, "Ownership bean must be registered for CurrentUser to fall back to");
        assertNotNull(detectionEventRepositoryPort, "DetectionEventRepositoryPort bean must be registered (docs/MVP2-PLAN.md E-a)");
        assertNotNull(eventController, "EventController must resolve its constructor dependency (docs/MVP2-PLAN.md E-a)");
        assertNotNull(fleetSummaryService, "FleetSummaryService bean must be registered (docs/MVP3-PLAN.md C-a)");
        assertNotNull(fleetController, "FleetController must resolve its constructor dependency (docs/MVP3-PLAN.md C-a)");
    }

    @Test
    void bothRtspAndMjpegFeedTransmittersAreWiredAsFeedTransmitterPortBeans() {
        assertTrue(feedTransmitterPorts.stream().anyMatch(RtspFeedTransmitter.class::isInstance),
                "docs/CYCLES-PLAN.md §3's transport=rtsp simulations need a real FeedTransmitterPort");
        assertTrue(feedTransmitterPorts.stream().anyMatch(MjpegFeedTransmitter.class::isInstance),
                "docs/CYCLES-PLAN.md §5's transport=mjpeg simulations need a real FeedTransmitterPort");
    }

    @Test
    void feedTransmitterRegistryResolvesRtspAndMjpegByProtocol() {
        FeedSpec rtspSpec = new FeedSpec("rtsp", URI.create("file:///tmp/clip.mp4"), Map.of());
        FeedSpec mjpegSpec = new FeedSpec("mjpeg", URI.create("file:///tmp/clip.mp4"), Map.of());

        assertInstanceOf(RtspFeedTransmitter.class, feedTransmitterRegistry.transmitterFor(rtspSpec));
        assertInstanceOf(MjpegFeedTransmitter.class, feedTransmitterRegistry.transmitterFor(mjpegSpec));
    }

    /** docs/MVP2-PLAN.md X-a: RX half of the MAVLink TX/RX pair. */
    @Test
    void mavlinkTelemetrySourceIsWiredAsATelemetrySourcePortBean() {
        assertTrue(telemetrySourcePorts.stream().anyMatch(MavlinkTelemetrySource.class::isInstance),
                "docs/MVP2-PLAN.md X-a's mavlink-protocol telemetry devices need a real TelemetrySourcePort");
    }

    /** docs/MVP2-PLAN.md X-a: TX half of the MAVLink TX/RX pair. */
    @Test
    void mavlinkFeedTransmitterIsWiredAsAFeedTransmitterPortBeanAndResolvesByProtocol() {
        assertTrue(feedTransmitterPorts.stream().anyMatch(MavlinkFeedTransmitter.class::isInstance),
                "docs/MVP2-PLAN.md X-a's mavlink feed transmitter must be a registered FeedTransmitterPort bean");

        FeedSpec mavlinkSpec = new FeedSpec("mavlink", URI.create("udp://127.0.0.1:14550"), Map.of("route", "1,1;2,2"));
        assertInstanceOf(MavlinkFeedTransmitter.class, feedTransmitterRegistry.transmitterFor(mavlinkSpec));
    }
}

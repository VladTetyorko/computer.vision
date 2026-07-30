package com.drones.vision.app;

import com.drones.vision.adapter.mavlink.MavlinkFeedTransmitter;
import com.drones.vision.adapter.mavlink.MavlinkFlightCommander;
import com.drones.vision.adapter.mavlink.MavlinkTelemetrySource;
import com.drones.vision.adapter.mjpeg.MjpegFeedTransmitter;
import com.drones.vision.adapter.rtsp.RtspFeedTransmitter;
import com.drones.vision.api.AssetImageController;
import com.drones.vision.api.DeviceProbeController;
import com.drones.vision.api.EventController;
import com.drones.vision.api.FleetController;
import com.drones.vision.api.FlightCommandController;
import com.drones.vision.api.GeofenceController;
import com.drones.vision.api.SimulationController;
import com.drones.vision.application.AssetService;
import com.drones.vision.application.CategoryService;
import com.drones.vision.application.DeviceService;
import com.drones.vision.application.FeedTransmitterRegistry;
import com.drones.vision.application.FleetSummaryService;
import com.drones.vision.application.FlightCommandService;
import com.drones.vision.application.GeofenceMonitor;
import com.drones.vision.application.GeofenceService;
import com.drones.vision.application.ProbeService;
import com.drones.vision.application.ReplayService;
import com.drones.vision.application.SimulationService;
import com.drones.vision.api.CurrentUser;
import com.drones.vision.domain.model.FeedSpec;
import com.drones.vision.domain.port.out.AssetImageRepositoryPort;
import com.drones.vision.domain.port.out.AssetRepositoryPort;
import com.drones.vision.domain.port.out.AssetUsageRepositoryPort;
import com.drones.vision.domain.port.out.AuditTrailPort;
import com.drones.vision.domain.port.out.CategoryRepositoryPort;
import com.drones.vision.domain.port.out.DetectionEventRepositoryPort;
import com.drones.vision.domain.port.out.FeedTransmitterPort;
import com.drones.vision.domain.port.out.FlightCommandPort;
import com.drones.vision.domain.port.out.GeofenceRepositoryPort;
import com.drones.vision.domain.port.out.TelemetryRepositoryPort;
import com.drones.vision.domain.port.out.TelemetrySourcePort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
 *
 * <p>Further extended for docs/OPS-CORE-PLAN.md §G (geofencing): {@link GeofenceRepositoryPort}/
 * {@link GeofenceMonitor}/{@link GeofenceService} are asserted here too, for the same "small,
 * focused, asset-model-adjacent bean" reasoning as {@link ReplayService} above, and {@link
 * GeofenceController} is asserted to resolve its constructor dependency, mirroring {@link
 * FleetController}'s own assertion.
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

    /** docs/U-AUTH-PLAN.md wave 3: the request-identity seam CurrentUser now delegates to (dev principal when auth disabled). */
    @Autowired
    private CurrentUser currentUser;

    /** Simulated-feed resume-on-boot (vision-application/MODULE.md's own design sketch) — always registered, resolves to a no-op with default (persistence-disabled) properties. */
    @Autowired
    private ApplicationRunner simulationResumeRunner;

    /** docs/UX-REWORK-PLAN.md §U-d item 3: CONTRACT 1's test-before-save connection probe. */
    @Autowired
    private ProbeService probeService;

    /** docs/UX-REWORK-PLAN.md §U-d item 3: {@code POST /api/devices/probe}. */
    @Autowired
    private DeviceProbeController deviceProbeController;

    /** docs/UX-REWORK-PLAN.md §U-d item 3: CONTRACT 2's asset image store (in-memory by default). */
    @Autowired
    private AssetImageRepositoryPort assetImageRepositoryPort;

    /** docs/UX-REWORK-PLAN.md §U-d item 3: {@code PUT}/{@code GET}/{@code DELETE /api/assets/{id}/image}. */
    @Autowired
    private AssetImageController assetImageController;

    /** docs/OPS-CORE-PLAN.md §G: geofence zone CRUD (in-memory store by default). */
    @Autowired
    private GeofenceRepositoryPort geofenceRepositoryPort;

    /** docs/OPS-CORE-PLAN.md §G: breach evaluation on the telemetry hot path. */
    @Autowired
    private GeofenceMonitor geofenceMonitor;

    /** docs/OPS-CORE-PLAN.md §G: the CRUD service behind {@code GeofenceController}. */
    @Autowired
    private GeofenceService geofenceService;

    /** docs/OPS-CORE-PLAN.md §G: {@code GET/POST /api/geofences}, {@code PUT}/{@code DELETE /api/geofences/{id}}. */
    @Autowired
    private GeofenceController geofenceController;

    /** docs/DRONE-INFRA-PLAN.md I-e Stage 1: the guarded return-to-home command service. */
    @Autowired
    private FlightCommandService flightCommandService;

    /** docs/DRONE-INFRA-PLAN.md I-e Stage 1: the one {@link FlightCommandPort} bean in this context. */
    @Autowired
    private FlightCommandPort flightCommandPort;

    /** docs/DRONE-INFRA-PLAN.md I-e Stage 1: the concrete adapter {@link #flightCommandPort} resolves to. */
    @Autowired
    private MavlinkFlightCommander mavlinkFlightCommander;

    /** docs/DRONE-INFRA-PLAN.md I-e Stage 1: {@code POST /api/assets/{id}/return-home}. */
    @Autowired
    private FlightCommandController flightCommandController;

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
        assertNotNull(currentUser, "CurrentUser must resolve its PrincipalResolver seam (docs/U-AUTH-PLAN.md wave 3)");
        assertNotNull(detectionEventRepositoryPort, "DetectionEventRepositoryPort bean must be registered (docs/MVP2-PLAN.md E-a)");
        assertNotNull(eventController, "EventController must resolve its constructor dependency (docs/MVP2-PLAN.md E-a)");
        assertNotNull(fleetSummaryService, "FleetSummaryService bean must be registered (docs/MVP3-PLAN.md C-a)");
        assertNotNull(fleetController, "FleetController must resolve its constructor dependency (docs/MVP3-PLAN.md C-a)");
        assertNotNull(simulationResumeRunner, "simulated-feed resume-on-boot ApplicationRunner bean must be registered");
        assertNotNull(probeService, "ProbeService bean must be registered (docs/UX-REWORK-PLAN.md §U-d item 3)");
        assertNotNull(deviceProbeController, "DeviceProbeController must resolve its constructor dependency");
        assertNotNull(assetImageRepositoryPort, "AssetImageRepositoryPort bean must be registered (docs/UX-REWORK-PLAN.md §U-d item 3)");
        assertNotNull(assetImageController, "AssetImageController must resolve its constructor dependency");
        assertNotNull(geofenceRepositoryPort, "GeofenceRepositoryPort bean must be registered (docs/OPS-CORE-PLAN.md §G)");
        assertNotNull(geofenceMonitor, "GeofenceMonitor bean must be registered (docs/OPS-CORE-PLAN.md §G)");
        assertNotNull(geofenceService, "GeofenceService bean must be registered (docs/OPS-CORE-PLAN.md §G)");
        assertNotNull(geofenceController, "GeofenceController must resolve its constructor dependency (docs/OPS-CORE-PLAN.md §G)");
        assertNotNull(flightCommandService, "FlightCommandService bean must be registered (docs/DRONE-INFRA-PLAN.md I-e Stage 1)");
        assertNotNull(flightCommandController, "FlightCommandController must resolve its constructor dependency (docs/DRONE-INFRA-PLAN.md I-e Stage 1)");
    }

    /**
     * docs/DRONE-INFRA-PLAN.md I-e Stage 1: {@link #flightCommandService} is constructed against
     * the same {@link FlightCommandPort} bean the context resolves elsewhere — there is only one,
     * {@link #mavlinkFlightCommander}, so the two autowired references above must be the exact same
     * instance.
     */
    @Test
    void flightCommandServiceIsWiredAgainstTheMavlinkCommander() {
        assertSame(mavlinkFlightCommander, flightCommandPort,
                "the FlightCommandPort bean FlightCommandService is constructed with must be the MavlinkFlightCommander bean");
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

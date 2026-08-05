package com.drones.vision.app;

import com.drones.vision.adapter.mavlink.MavlinkFeedTransmitter;
import com.drones.vision.adapter.mavlink.MavlinkFlightCommander;
import com.drones.vision.adapter.mavlink.MavlinkManualControlSender;
import com.drones.vision.adapter.mavlink.MavlinkTelemetrySource;
import com.drones.vision.adapter.mjpeg.MjpegFeedTransmitter;
import com.drones.vision.adapter.rtsp.RtspFeedTransmitter;
import com.drones.vision.api.controller.ActivityController;
import com.drones.vision.api.controller.AssetImageController;
import com.drones.vision.api.controller.AssetStatsController;
import com.drones.vision.api.controller.AssignmentController;
import com.drones.vision.api.controller.CvModelsController;
import com.drones.vision.api.controller.DeviceProbeController;
import com.drones.vision.api.controller.EventController;
import com.drones.vision.api.controller.FleetController;
import com.drones.vision.api.controller.FlightCommandController;
import com.drones.vision.api.controller.GeofenceController;
import com.drones.vision.api.controller.GroupAdminController;
import com.drones.vision.api.controller.MapDrawingsController;
import com.drones.vision.api.controller.MapLayersController;
import com.drones.vision.api.controller.MapMarksController;
import com.drones.vision.api.controller.SimulationController;
import com.drones.vision.api.controller.UserAdminController;
import com.drones.vision.application.identity.ActivityService;
import com.drones.vision.application.asset.AssetService;
import com.drones.vision.application.identity.AssignmentService;
import com.drones.vision.application.scope.ScopeResolver;
import com.drones.vision.application.asset.AssetStatsService;
import com.drones.vision.application.category.CategoryService;
import com.drones.vision.application.device.DeviceService;
import com.drones.vision.application.pipeline.FeedTransmitterRegistry;
import com.drones.vision.application.fleet.FleetSummaryService;
import com.drones.vision.application.flight.FlightCommandService;
import com.drones.vision.application.geofence.GeofenceMonitor;
import com.drones.vision.application.geofence.GeofenceService;
import com.drones.vision.application.flight.ManualControlService;
import com.drones.vision.application.map.DrawingService;
import com.drones.vision.application.map.LayerResolver;
import com.drones.vision.application.map.MapAccessPolicy;
import com.drones.vision.application.map.MapLayerService;
import com.drones.vision.application.mark.MarkService;
import com.drones.vision.api.dto.CvModelResponse;
import com.drones.vision.application.device.ProbeService;
import com.drones.vision.application.replay.ReplayService;
import com.drones.vision.application.simulation.SimulationService;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.domain.model.FeedSpec;
import com.drones.vision.domain.model.LayerKind;
import com.drones.vision.domain.port.out.AssetImageRepositoryPort;
import com.drones.vision.domain.port.out.AssetRepositoryPort;
import com.drones.vision.domain.port.out.AssetUsageRepositoryPort;
import com.drones.vision.domain.port.out.AssignmentRepositoryPort;
import com.drones.vision.domain.port.out.AuditTrailPort;
import com.drones.vision.domain.port.out.CategoryRepositoryPort;
import com.drones.vision.domain.port.out.DetectionEventRepositoryPort;
import com.drones.vision.domain.port.out.DrawingRepositoryPort;
import com.drones.vision.domain.port.out.FeedTransmitterPort;
import com.drones.vision.domain.port.out.FlightCommandPort;
import com.drones.vision.domain.port.out.GeofenceRepositoryPort;
import com.drones.vision.domain.port.out.MapLayerRepositoryPort;
import com.drones.vision.domain.port.out.MarkRepositoryPort;
import com.drones.vision.domain.port.out.TelemetryRepositoryPort;
import com.drones.vision.domain.port.out.TelemetrySourcePort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 *
 * <p>Further extended for docs/ASSET-MANAGER-PAGE-PLAN.md Wave A: {@link AssetStatsService} is
 * asserted here too, for the same "small, focused, asset-model-adjacent bean" reasoning as {@link
 * ReplayService} above, and {@link AssetStatsController} is asserted to resolve its constructor
 * dependencies, mirroring {@link FleetController}'s own assertion.
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

    /** docs/ASSET-MANAGER-PAGE-PLAN.md Wave A: the manager page's per-asset flight-stats aggregate. */
    @Autowired
    private AssetStatsService assetStatsService;

    /** docs/ASSET-MANAGER-PAGE-PLAN.md Wave A: {@code GET /api/assets/{id}/stats}. */
    @Autowired
    private AssetStatsController assetStatsController;

    /** docs/RC-CONTROL-PHASE1-PLAN.md R4: the watchdog-supervised RC-relay session service behind {@code /ws/manual-control}. */
    @Autowired
    private ManualControlService manualControlService;

    /** docs/RC-CONTROL-PHASE1-PLAN.md R4: the one {@code ManualControlPort} bean {@link #manualControlService} is constructed with. */
    @Autowired
    private MavlinkManualControlSender mavlinkManualControlSender;

    /** docs/U-SCOPE-PLAN.md slice 2 feature 1: visibility-scope resolution (unconditional bean). */
    @Autowired
    private ScopeResolver scopeResolver;

    /** docs/U-SCOPE-PLAN.md slice 2 feature 2: the pilot→asset assignment service. */
    @Autowired
    private AssignmentService assignmentService;

    /** docs/U-SCOPE-PLAN.md slice 2 feature 2: the assignment join store (in-memory by default). */
    @Autowired
    private AssignmentRepositoryPort assignmentRepositoryPort;

    /** docs/U-SCOPE-PLAN.md slice 2 feature 7: a user's own activity feed service. */
    @Autowired
    private ActivityService activityService;

    /** docs/U-SCOPE-PLAN.md slice 2: {@code PUT/DELETE /api/assets/{id}/pilots/*}, {@code GET /api/me/assignments}. */
    @Autowired
    private AssignmentController assignmentController;

    /** docs/U-SCOPE-PLAN.md slice 2: {@code GET /api/me/activity}. */
    @Autowired
    private ActivityController activityController;

    /** docs/U-SCOPE-PLAN.md slice 2: {@code GET/POST /api/users}, {@code POST /api/users/{id}/enabled}. */
    @Autowired
    private UserAdminController userAdminController;

    /** docs/U-SCOPE-PLAN.md slice 2: {@code GET/POST /api/groups}. */
    @Autowired
    private GroupAdminController groupAdminController;

    /** docs/CV-CONTROL-PLAN.md §4: the static, config-backed detection-model roster bean. */
    @Autowired
    private List<CvModelResponse> cvModelRoster;

    /** docs/CV-CONTROL-PLAN.md §4: {@code GET /api/cv/models}. */
    @Autowired
    private CvModelsController cvModelsController;

    /** docs/TACTICAL-MARKS-PLAN.md M2: the mark repository store (in-memory by default). */
    @Autowired
    private MarkRepositoryPort markRepositoryPort;

    /** docs/TACTICAL-MARKS-PLAN.md M4, reworked docs/MAP-REWORK-PLAN.md §3: the mark CRUD/geolocate/verify/promote service. */
    @Autowired
    private MarkService markService;

    /** docs/MAP-REWORK-PLAN.md §3: the map's authorization model, shared by every map service. */
    @Autowired
    private MapAccessPolicy mapAccessPolicy;

    /** docs/MAP-REWORK-PLAN.md §3: the shared layer-lookup/default-layer collaborator. */
    @Autowired
    private LayerResolver layerResolver;

    /** docs/MAP-REWORK-PLAN.md §3: layer CRUD + grants. */
    @Autowired
    private MapLayerService mapLayerService;

    /** docs/MAP-REWORK-PLAN.md §3: drawings (line/polygon/arrow/text). */
    @Autowired
    private DrawingService drawingService;

    /** docs/MAP-REWORK-PLAN.md §2.3: the layer store (in-memory by default). */
    @Autowired
    private MapLayerRepositoryPort mapLayerRepositoryPort;

    /** docs/MAP-REWORK-PLAN.md §2.3: the drawing store (in-memory by default). */
    @Autowired
    private DrawingRepositoryPort drawingRepositoryPort;

    /** docs/MAP-REWORK-PLAN.md §4.1: {@code /api/map/layers} CRUD + grants. */
    @Autowired
    private MapLayersController mapLayersController;

    /** docs/MAP-REWORK-PLAN.md §4.1: {@code /api/map/marks} CRUD + geolocate/verify/promote. */
    @Autowired
    private MapMarksController mapMarksController;

    /** docs/MAP-REWORK-PLAN.md §4.1: {@code /api/map/drawings} CRUD. */
    @Autowired
    private MapDrawingsController mapDrawingsController;

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
        assertNotNull(assetStatsService, "AssetStatsService bean must be registered (docs/ASSET-MANAGER-PAGE-PLAN.md Wave A)");
        assertNotNull(assetStatsController, "AssetStatsController must resolve its constructor dependencies (docs/ASSET-MANAGER-PAGE-PLAN.md Wave A)");
        assertNotNull(scopeResolver, "ScopeResolver bean must be registered (docs/U-SCOPE-PLAN.md slice 2)");
        assertNotNull(assignmentService, "AssignmentService bean must be registered (docs/U-SCOPE-PLAN.md slice 2)");
        assertNotNull(assignmentRepositoryPort, "AssignmentRepositoryPort bean must be registered (docs/U-SCOPE-PLAN.md slice 2)");
        assertNotNull(activityService, "ActivityService bean must be registered (docs/U-SCOPE-PLAN.md slice 2)");
        assertNotNull(assignmentController, "AssignmentController must resolve its constructor dependencies (docs/U-SCOPE-PLAN.md slice 2)");
        assertNotNull(activityController, "ActivityController must resolve its constructor dependencies (docs/U-SCOPE-PLAN.md slice 2)");
        assertNotNull(userAdminController, "UserAdminController must resolve its constructor dependency (docs/U-SCOPE-PLAN.md slice 2)");
        assertNotNull(groupAdminController, "GroupAdminController must resolve its constructor dependency (docs/U-SCOPE-PLAN.md slice 2)");
        assertNotNull(cvModelRoster, "cvModelRoster bean must be registered (docs/CV-CONTROL-PLAN.md §4)");
        assertTrue(cvModelRoster.stream().anyMatch(m -> "yolo26n.pt".equals(m.id())),
                "cvModelRoster must include the default yolo26n.pt model (docs/CV-CONTROL-PLAN.md §4)");
        assertNotNull(cvModelsController, "CvModelsController must resolve its constructor dependency (docs/CV-CONTROL-PLAN.md §4)");
        assertNotNull(manualControlService, "ManualControlService bean must be registered (docs/RC-CONTROL-PHASE1-PLAN.md R4)");
        assertNotNull(mavlinkManualControlSender, "MavlinkManualControlSender bean must be registered (docs/RC-CONTROL-PHASE1-PLAN.md R4)");
        assertNotNull(markRepositoryPort, "MarkRepositoryPort bean must be registered (docs/TACTICAL-MARKS-PLAN.md M2)");
        assertNotNull(markService, "MarkService bean must be registered (docs/TACTICAL-MARKS-PLAN.md M4)");
        assertNotNull(mapAccessPolicy, "MapAccessPolicy bean must be registered (docs/MAP-REWORK-PLAN.md §3)");
        assertNotNull(layerResolver, "LayerResolver bean must be registered (docs/MAP-REWORK-PLAN.md §3)");
        assertNotNull(mapLayerService, "MapLayerService bean must be registered (docs/MAP-REWORK-PLAN.md §3)");
        assertNotNull(drawingService, "DrawingService bean must be registered (docs/MAP-REWORK-PLAN.md §3)");
        assertNotNull(mapLayerRepositoryPort, "MapLayerRepositoryPort bean must be registered (docs/MAP-REWORK-PLAN.md §2.3)");
        assertNotNull(drawingRepositoryPort, "DrawingRepositoryPort bean must be registered (docs/MAP-REWORK-PLAN.md §2.3)");
        assertNotNull(mapLayersController, "MapLayersController must resolve its constructor dependencies (docs/MAP-REWORK-PLAN.md §4.1)");
        assertNotNull(mapMarksController, "MapMarksController must resolve its constructor dependencies (docs/MAP-REWORK-PLAN.md §4.1)");
        assertNotNull(mapDrawingsController, "MapDrawingsController must resolve its constructor dependencies (docs/MAP-REWORK-PLAN.md §4.1)");
    }

    /**
     * docs/MAP-REWORK-PLAN.md §3: the COP layer is ensured at startup, in both persistence modes.
     * This context boots with the default {@code vision.persistence.enabled=false}, so the
     * {@code mapLayerBootstrapRunner} {@code ApplicationRunner} is what created it — and because
     * {@code LayerResolver#copLayerId()} is a synchronized find-or-create, asking again must return
     * the same id rather than mint a second COP layer.
     */
    @Test
    void theCopLayerExistsOnceAfterStartup() {
        assertNotNull(mapLayerService.copLayerId(), "the COP layer must exist after startup");
        assertEquals(mapLayerService.copLayerId(), mapLayerService.copLayerId(),
                "copLayerId() is idempotent -- a second call must not create a second COP layer");
        assertEquals(1, mapLayerRepositoryPort.findAll().stream()
                        .filter(layer -> layer.kind() == LayerKind.COP).count(),
                "exactly one COP layer exists per deployment");
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

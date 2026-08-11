package com.drones.vision.app.config.wiring;

import com.drones.vision.adapter.cvgrpc.GrpcDetectionPort;
import com.drones.vision.api.live.LiveUpdateRegistry;
import com.drones.vision.app.config.properties.VisionApplicationProperties;
import com.drones.vision.app.config.properties.VisionCvProperties;
import com.drones.vision.app.config.properties.VisionLiveProperties;
import com.drones.vision.app.config.properties.VisionPersistenceProperties;
import com.drones.vision.app.config.properties.VisionPublishProperties;
import com.drones.vision.app.config.properties.VisionRcProperties;
import com.drones.vision.app.config.properties.VisionSimulationProperties;
import com.drones.vision.app.config.properties.VisionTrackingProperties;
import com.drones.vision.app.bootstrap.SimulationResumeRunner;
import com.drones.vision.app.devsupport.*;
import com.drones.vision.app.events.DetectionSessionCleanupEventPublisher;
import com.drones.vision.app.events.LiveUpdateAuditTrail;
import com.drones.vision.app.events.LiveUpdateDetectionEventRepository;
import com.drones.vision.app.events.LiveUpdateEventPublisher;
import com.drones.vision.application.asset.*;
import com.drones.vision.application.category.*;
import com.drones.vision.application.device.*;
import com.drones.vision.application.fleet.*;
import com.drones.vision.application.flight.*;
import com.drones.vision.application.geofence.*;
import com.drones.vision.application.map.*;
import com.drones.vision.application.mark.*;
import com.drones.vision.application.pipeline.*;
import com.drones.vision.application.replay.*;
import com.drones.vision.application.simulation.*;
import com.drones.vision.application.stream.*;
import com.drones.vision.application.usage.*;
import com.drones.vision.domain.port.out.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Wires the {@code vision-application} service layer — the largest slice of what used to be one
 * 825-line {@code WiringConfiguration} (docs/LAYERING-REFACTOR-PLAN.md wave D): every {@code
 * DefaultXService}, the event/audit/live-update decorator chains, and the two {@code
 * ApplicationRunner}s. This is the only place in the codebase allowed to know about both the
 * application layer and concrete adapters/devsupport fallbacks for these ports — enforced by
 * {@code ArchitectureTest}.
 *
 * <p>Ports that don't yet have a real adapter are wired to in-process dev-support fallbacks so the
 * platform runs end to end from Phase 0 onward. The server-push data plane (docs/REALTIME-PLAN.md
 * §4): {@link #liveUpdatePublisherPort} selects between the real {@code LiveUpdateRegistry}
 * (vision-api) and {@code NoopLiveUpdatePublisher} per {@link VisionLiveProperties#enabled()}
 * (default {@code true}), threaded unconditionally into {@link #usageTracker}/{@link
 * #streamService}; {@link #auditTrailPort}/{@link #eventPublisherPort}/{@link
 * #detectionEventRepositoryPort} each gain one more decorator ({@link LiveUpdateAuditTrail}/{@link
 * LiveUpdateEventPublisher}/{@link LiveUpdateDetectionEventRepository}) only when that property is
 * {@code true}.
 */
@Configuration
@EnableConfigurationProperties({VisionCvProperties.class, VisionLiveProperties.class, VisionRcProperties.class,
        VisionApplicationProperties.class, VisionPublishProperties.class, VisionPersistenceProperties.class,
        VisionSimulationProperties.class})
public class ApplicationServiceWiring {

    /**
     * The base implementation is always {@link LoggingEventPublisher}. When {@link
     * VisionCvProperties#enabled()} is {@code true} and {@link #detectionPort} (from {@code
     * CvWiring}) resolved to a {@code GrpcDetectionPort}, the bean is instead a {@link
     * DetectionSessionCleanupEventPublisher} wrapping it — the wiring-layer seam that forwards a
     * {@code STREAM_STOPPED} event's stream id into {@code GrpcDetectionPort#streamEnded}. When
     * {@link VisionLiveProperties#enabled()} is {@code true}, the result is further wrapped in
     * {@link LiveUpdateEventPublisher}.
     */
    @Bean
    public EventPublisherPort eventPublisherPort(DetectionPort detectionPort, VisionCvProperties cvProperties,
                                                  LiveUpdatePublisherPort liveUpdatePublisherPort,
                                                  VisionLiveProperties liveProperties) {
        EventPublisherPort delegate = new LoggingEventPublisher();
        if (cvProperties.enabled() && detectionPort instanceof GrpcDetectionPort grpcDetectionPort) {
            delegate = new DetectionSessionCleanupEventPublisher(delegate, grpcDetectionPort);
        }
        if (liveProperties.enabled()) {
            delegate = new LiveUpdateEventPublisher(delegate, liveUpdatePublisherPort);
        }
        return delegate;
    }

    /**
     * Selects the {@link LiveUpdatePublisherPort} implementation per {@link
     * VisionLiveProperties#enabled()} (docs/REALTIME-PLAN.md §4, item 4): {@code true} (the
     * default) wires the real {@code LiveUpdateRegistry} (vision-api, component-scanned); {@code
     * false} wires {@link NoopLiveUpdatePublisher}.
     */
    @Bean
    public LiveUpdatePublisherPort liveUpdatePublisherPort(VisionLiveProperties properties,
                                                            ObjectProvider<LiveUpdateRegistry> registry) {
        if (properties.enabled()) {
            return registry.getObject();
        }
        return new NoopLiveUpdatePublisher();
    }

    /**
     * The stateful, watchdog-supervised RC-relay session service (docs/RC-CONTROL-PHASE1-PLAN.md
     * §2, R2) behind {@code ManualControlWebSocketHandler} (vision-api, component-scanned). {@code
     * manualControlPort} resolves to {@code TelemetryWiring#mavlinkManualControlSender}, the one
     * {@link ManualControlPort} bean in this context today.
     *
     * <p>Uses {@code DefaultManualControlService}'s <b>6-arg canonical constructor</b> — not either
     * convenience constructor — so {@link VisionRcProperties#watchdogTimeoutMs()} actually takes
     * effect; the convenience constructors hardcode {@code DEFAULT_WATCHDOG_TIMEOUT_MS}.
     */
    @Bean
    public ManualControlService manualControlService(AssetService assetService, ManualControlPort manualControlPort,
                                                       AuditTrailPort auditTrailPort, VisionRcProperties rcProperties) {
        return new DefaultManualControlService(assetService, manualControlPort, auditTrailPort, Clock.systemUTC(),
                rcWatchdogScheduler(), rcProperties.watchdogTimeoutMs());
    }

    private static ScheduledExecutorService rcWatchdogScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "rc-watchdog");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    public DeviceService deviceService(DeviceRepositoryPort deviceRepositoryPort,
                                        StreamService streamService,
                                        AuditTrailPort auditTrailPort,
                                        EventPublisherPort eventPublisherPort) {
        return new DefaultDeviceService(deviceRepositoryPort, streamService, auditTrailPort, eventPublisherPort);
    }

    /**
     * Append-only record of who changed the fleet. In-memory, unconditionally. When {@link
     * VisionLiveProperties#enabled()} is {@code true}, wrapped in {@link LiveUpdateAuditTrail},
     * which announces a "fleet changed" live update for every recorded entry.
     */
    @Bean
    public AuditTrailPort auditTrailPort(LiveUpdatePublisherPort liveUpdatePublisherPort,
                                          VisionLiveProperties liveProperties) {
        AuditTrailPort delegate = new InMemoryAuditTrail();
        if (liveProperties.enabled()) {
            return new LiveUpdateAuditTrail(delegate, liveUpdatePublisherPort);
        }
        return delegate;
    }

    /**
     * Debounced detection events (docs/MVP2-PLAN.md §E, E-a). In-memory, unconditionally. When
     * {@link VisionLiveProperties#enabled()} is {@code true}, wrapped in {@link
     * LiveUpdateDetectionEventRepository}, which announces every {@code save} as a live update.
     */
    @Bean
    public DetectionEventRepositoryPort detectionEventRepositoryPort(LiveUpdatePublisherPort liveUpdatePublisherPort,
                                                                       VisionLiveProperties liveProperties) {
        DetectionEventRepositoryPort delegate = new InMemoryDetectionEventRepository();
        if (liveProperties.enabled()) {
            return new LiveUpdateDetectionEventRepository(delegate, liveUpdatePublisherPort);
        }
        return delegate;
    }

    /**
     * Evaluates live telemetry against the enabled {@code GeofenceZone} set and raises {@code
     * GEOFENCE_BREACH} events on edge transitions (docs/OPS-CORE-PLAN.md §G) — threaded into
     * {@link #usageTracker} below as a nullable-by-convention but always-real collaborator.
     */
    @Bean
    public GeofenceMonitor geofenceMonitor(GeofenceRepositoryPort geofenceRepositoryPort,
                                            EventPublisherPort eventPublisherPort,
                                            LiveUpdatePublisherPort liveUpdatePublisherPort) {
        return new GeofenceMonitor(geofenceRepositoryPort, eventPublisherPort, liveUpdatePublisherPort);
    }

    /**
     * CRUD/list over geofence zones (docs/OPS-CORE-PLAN.md §G) behind {@code GeofenceController}
     * (vision-api, component-scanned) — a one-line assembly, mirroring {@link #replayService}'s
     * shape.
     */
    @Bean
    public GeofenceService geofenceService(GeofenceRepositoryPort geofenceRepositoryPort,
                                            GeofenceMonitor geofenceMonitor) {
        return new DefaultGeofenceService(geofenceRepositoryPort, geofenceMonitor);
    }

    /**
     * Drives {@link com.drones.vision.domain.model.AssetUsage} lifecycle and telemetry sampling
     * from {@link StreamService}'s start/stop notifications. {@code liveUpdatePublisherPort} and
     * {@code geofenceMonitor} are threaded through unconditionally — both are always real beans.
     */
    @Bean
    public UsageTracker usageTracker(AssetRepositoryPort assetRepositoryPort,
                                      DeviceRepositoryPort deviceRepositoryPort,
                                      AssetUsageRepositoryPort assetUsageRepositoryPort,
                                      TelemetryRepositoryPort telemetryRepositoryPort,
                                      List<TelemetrySourcePort> telemetrySources,
                                      LiveUpdatePublisherPort liveUpdatePublisherPort,
                                      GeofenceMonitor geofenceMonitor) {
        return new UsageTracker(assetRepositoryPort, deviceRepositoryPort, assetUsageRepositoryPort,
                telemetryRepositoryPort, telemetrySources, liveUpdatePublisherPort, geofenceMonitor);
    }

    /**
     * The map's authorization model (docs/MAP-REWORK-PLAN.md §3) — pure, stateless, no ports, so one
     * shared singleton serves every map service and {@code CurrentUser#viewer()}'s consumers alike.
     */
    @Bean
    public MapAccessPolicy mapAccessPolicy() {
        return new MapAccessPolicy();
    }

    /**
     * The shared layer-lookup/default-layer collaborator every map service composes
     * (docs/MAP-REWORK-PLAN.md §3) — deliberately one bean rather than three instances, because its
     * {@code copLayerId()}/{@code defaultLayerFor()} are {@code synchronized} find-or-create methods
     * whose idempotence depends on a single instance guarding a single repository.
     */
    @Bean
    public LayerResolver layerResolver(MapLayerRepositoryPort mapLayerRepositoryPort,
                                        LiveUpdatePublisherPort liveUpdatePublisherPort) {
        return new LayerResolver(mapLayerRepositoryPort, liveUpdatePublisherPort);
    }

    /**
     * Layer CRUD + grants (docs/MAP-REWORK-PLAN.md §3) behind {@code MapLayersController}
     * (vision-api, component-scanned). Takes the mark/drawing repositories directly — not their
     * services — because the only thing it does with them is cascade a layer deletion, for which the
     * services' own viewer-gated methods would be both wrong (the cascade is already authorized) and
     * circular.
     */
    @Bean
    public MapLayerService mapLayerService(LayerResolver layerResolver, MarkRepositoryPort markRepositoryPort,
                                            DrawingRepositoryPort drawingRepositoryPort,
                                            LiveUpdatePublisherPort liveUpdatePublisherPort,
                                            MapAccessPolicy mapAccessPolicy) {
        return new DefaultMapLayerService(layerResolver, markRepositoryPort, drawingRepositoryPort,
                liveUpdatePublisherPort, mapAccessPolicy);
    }

    /**
     * The tactical marks half of the common operational picture (docs/MAP-REWORK-PLAN.md §3) behind
     * {@code MapMarksController} (vision-api, component-scanned) — reworked in place from the
     * docs/TACTICAL-MARKS-PLAN.md M4 bean this replaces, which took no policy and no layer resolver.
     * {@code usageTracker} still backs the cockpit "geolocate" action ({@code
     * UsageTracker#latestTelemetry}); {@code liveUpdatePublisherPort} is always a real bean, so every
     * mutation is announced on the {@code map} SSE topic unconditionally.
     */
    @Bean
    public MarkService markService(MarkRepositoryPort markRepositoryPort, UsageTracker usageTracker,
                                    LiveUpdatePublisherPort liveUpdatePublisherPort,
                                    MapAccessPolicy mapAccessPolicy, LayerResolver layerResolver) {
        return new DefaultMarkService(markRepositoryPort, usageTracker, liveUpdatePublisherPort,
                mapAccessPolicy, layerResolver);
    }

    /**
     * Lines/polygons/arrows/text on the map (docs/MAP-REWORK-PLAN.md §3) behind {@code
     * MapDrawingsController} (vision-api, component-scanned) — a one-line assembly, mirroring
     * {@link #markService}'s shape minus the telemetry collaborator a drawing has no use for.
     */
    @Bean
    public DrawingService drawingService(DrawingRepositoryPort drawingRepositoryPort,
                                          LiveUpdatePublisherPort liveUpdatePublisherPort,
                                          MapAccessPolicy mapAccessPolicy, LayerResolver layerResolver) {
        return new DefaultDrawingService(drawingRepositoryPort, liveUpdatePublisherPort, mapAccessPolicy,
                layerResolver);
    }

    /**
     * Ensures the single COP layer exists before the first request can ask for it
     * (docs/MAP-REWORK-PLAN.md §3's "ensured at startup").
     *
     * <p>{@code copLayerId()} is a synchronized find-or-create and therefore already idempotent, so
     * this runner is not a correctness requirement — it is a timing one. Without it, the first
     * caller to promote a mark (or the first {@code GET /api/map/layers}) would be the one to create
     * the layer, which means the layer's {@code CREATED} SSE event would race that caller's own
     * response. Calling it once at startup makes the shared picture present from boot, in both
     * persistence modes: with Postgres {@code V12__map_layers.sql} has already inserted the row and
     * this call simply finds it; in memory, this call is what creates it.
     */
    @Bean
    public ApplicationRunner mapLayerBootstrapRunner(MapLayerService mapLayerService) {
        return args -> mapLayerService.copLayerId();
    }

    /**
     * {@code liveUpdatePublisherPort} is threaded through unconditionally, same reasoning as {@link
     * #usageTracker} above — every stream pipeline this service starts announces its completed
     * detection results regardless of {@link VisionLiveProperties#enabled()}.
     */
    @Bean
    public StreamService streamService(DeviceRepositoryPort deviceRepositoryPort,
                                        VideoSourceRegistry videoSourceRegistry,
                                        DetectionPort detectionPort,
                                        StreamPublisherPort streamPublisherPort,
                                        DetectionRepositoryPort detectionRepositoryPort,
                                        EventPublisherPort eventPublisherPort,
                                        UsageTracker usageTracker,
                                        OverlayPort overlayPort,
                                        DetectionEventRepositoryPort detectionEventRepositoryPort,
                                        LiveUpdatePublisherPort liveUpdatePublisherPort,
                                        VisionApplicationProperties applicationProperties,
                                        VisionTrackingProperties trackingProperties) {
        return new DefaultStreamService(deviceRepositoryPort, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisherPort, usageTracker, overlayPort,
                detectionEventRepositoryPort, liveUpdatePublisherPort,
                streamPipelineSettings(applicationProperties, trackingProperties));
    }

    /**
     * Maps {@link VisionApplicationProperties.Pipeline}/{@link VisionApplicationProperties.Extrapolation}
     * and {@link VisionTrackingProperties}' two read-model windows onto {@code
     * com.drones.vision.application.pipeline.StreamPipelineSettings} — the two backoff
     * pairs are bound in milliseconds but the settings record's own unit is nanoseconds, so this is
     * where the conversion happens, once.
     *
     * <p>The tracking window pair comes from {@code vision.tracking.*} rather than {@code
     * vision.application.*} (docs/TRACKING-ORCHESTRATION.md &sect;4.3): {@code stats-window-seconds}
     * is how far back {@code TrackingStatsWindow}'s duty-cycle counters reach — the number {@code GET
     * /api/streams/{id}/tracks} reports as {@code stats.windowSeconds} — and {@code
     * track-retention-seconds} is how long {@code TrackBook} keeps a track that stopped arriving.
     * Both configure per-stream <b>read models</b>, so unlike the mode/cadence seeds they apply to
     * every stream this instance starts from then on. Every default is byte-identical to the literal
     * the settings record's own convenience constructor uses.
     */
    static StreamPipelineSettings streamPipelineSettings(VisionApplicationProperties properties,
                                                          VisionTrackingProperties tracking) {
        VisionApplicationProperties.Pipeline pipeline = properties.pipeline();
        VisionApplicationProperties.Extrapolation extrapolation = properties.extrapolation();
        return new StreamPipelineSettings(pipeline.assumedSourceFps(), pipeline.measuredFpsEwmaAlpha(),
                pipeline.warmupFrames(), pipeline.minMeasuredFps(), pipeline.maxMeasuredFps(),
                TimeUnit.MILLISECONDS.toNanos(pipeline.detectionBackoff().initialMs()),
                TimeUnit.MILLISECONDS.toNanos(pipeline.detectionBackoff().maxMs()),
                TimeUnit.MILLISECONDS.toNanos(pipeline.sourceReopenBackoff().initialMs()),
                TimeUnit.MILLISECONDS.toNanos(pipeline.sourceReopenBackoff().maxMs()),
                extrapolation.maxMillis(), extrapolation.matchGate(),
                Duration.ofSeconds(tracking.statsWindowSeconds()),
                Duration.ofSeconds(tracking.trackRetentionSeconds()));
    }

    /**
     * Assets — creation, editing, lifecycle, and asset-level streaming. Devices are reached
     * through {@link DeviceService} rather than the repository, so rules that belong to a source
     * live in exactly one place.
     */
    @Bean
    public AssetService assetService(AssetRepositoryPort assetRepositoryPort,
                                      CategoryRepositoryPort categoryRepositoryPort,
                                      AssetUsageRepositoryPort assetUsageRepositoryPort,
                                      AuditTrailPort auditTrailPort,
                                      DeviceService deviceService,
                                      StreamService streamService) {
        return new DefaultAssetService(assetRepositoryPort, categoryRepositoryPort, assetUsageRepositoryPort,
                auditTrailPort, deviceService, streamService);
    }

    @Bean
    public CategoryService categoryService(CategoryRepositoryPort categoryRepositoryPort) {
        return new DefaultCategoryService(categoryRepositoryPort);
    }

    /**
     * Guarded flight command TX (docs/DRONE-INFRA-PLAN.md I-e, Stage 1 — "bring it home"): the
     * service behind {@code FlightCommandController} (vision-api, component-scanned). {@code
     * flightCommandPort} resolves to {@code TelemetryWiring#mavlinkFlightCommander}, the one
     * {@link FlightCommandPort} bean in this context today.
     */
    @Bean
    public FlightCommandService flightCommandService(AssetService assetService,
                                                       FlightCommandPort flightCommandPort,
                                                       AuditTrailPort auditTrailPort) {
        return new DefaultFlightCommandService(assetService, flightCommandPort, auditTrailPort);
    }

    /**
     * Flight replay (docs/MVP2-PLAN.md §R, R-a/R-a2) plus its recording/clip-export read
     * (docs/OPS-CORE-PLAN.md §R, R-b): the read side behind {@code UsageTimelineController}
     * (vision-api, component-scanned).
     */
    @Bean
    public ReplayService replayService(AssetUsageRepositoryPort assetUsageRepositoryPort,
                                        TelemetryRepositoryPort telemetryRepositoryPort,
                                        DetectionRepositoryPort detectionRepositoryPort,
                                        StreamPublisherPort streamPublisherPort,
                                        VisionApplicationProperties applicationProperties) {
        VisionApplicationProperties.Replay replay = applicationProperties.replay();
        return new DefaultReplayService(assetUsageRepositoryPort, telemetryRepositoryPort, detectionRepositoryPort,
                streamPublisherPort,
                new ReplayServiceSettings(replay.defaultMaxPoints(), replay.maxPointsCeiling(), replay.fetchLimit()));
    }

    /**
     * The fleet-wide "replay library" list (docs/NAV-IA-REDESIGN-PLAN.md Wave 4, F8): the read side
     * behind {@code UsageTimelineController}'s {@code GET /api/usages} (vision-api,
     * component-scanned) — a sibling read to {@link #replayService}'s per-usage detail, kept as its
     * own bean/service since it needs a different collaborator ({@link AssetRepositoryPort}, to
     * resolve each row's display name and enforce visibility) that {@link ReplayService} has no use
     * for.
     */
    @Bean
    public UsageService usageService(AssetUsageRepositoryPort assetUsageRepositoryPort,
                                      AssetRepositoryPort assetRepositoryPort) {
        return new DefaultUsageService(assetUsageRepositoryPort, assetRepositoryPort);
    }

    /**
     * The manager dashboard's single aggregated read (docs/MVP3-PLAN.md C-a): the read side behind
     * {@code FleetController} (vision-api, component-scanned).
     */
    @Bean
    public FleetSummaryService fleetSummaryService(AssetService assetService, StreamService streamService,
                                                     UsageTracker usageTracker,
                                                     DetectionEventRepositoryPort detectionEventRepositoryPort,
                                                     VisionApplicationProperties applicationProperties) {
        VisionApplicationProperties.Fleet fleet = applicationProperties.fleet();
        return new DefaultFleetSummaryService(assetService, streamService, usageTracker,
                detectionEventRepositoryPort, fleet.maxAssets(), fleet.openEventsScanLimit());
    }

    /**
     * One asset's flight-utilization stats (docs/ASSET-MANAGER-PAGE-PLAN.md, Wave A): the read
     * side behind {@code AssetStatsController}'s {@code GET /api/assets/{id}/stats} (vision-api,
     * component-scanned).
     */
    @Bean
    public AssetStatsService assetStatsService(AssetUsageRepositoryPort assetUsageRepositoryPort,
                                                UsageTracker usageTracker,
                                                VisionApplicationProperties applicationProperties) {
        return new DefaultAssetStatsService(assetUsageRepositoryPort, usageTracker,
                applicationProperties.stats().fetchLimit());
    }

    /**
     * Test-before-save connection probe (docs/UX-REWORK-PLAN.md §U-d item 3, UX-DESIGN.md §5.1):
     * the read side behind {@code DeviceProbeController} (vision-api, component-scanned). Reuses
     * {@code videoSourceRegistry} (the exact same adapter-selection {@link StreamService} itself
     * streams through, from {@code VideoSourceWiring}) plus the {@code List<TelemetrySourcePort>}
     * beans Spring already collects for {@link #usageTracker}.
     */
    @Bean
    public ProbeService probeService(VideoSourceRegistry videoSourceRegistry,
                                      List<TelemetrySourcePort> telemetrySources) {
        return new DefaultProbeService(videoSourceRegistry, telemetrySources);
    }

    /**
     * The one-call, zero-hardware simulation entry point (docs/CYCLES-PLAN.md §1b, §3, §5): turns
     * a video file path into a registered {@code simulated}-category asset via {@link
     * #assetService}, reusing every rule it already enforces. {@code feedTransmitterRegistry}
     * (from {@code FeedTransmitterWiring}) backs {@code transport=rtsp}/{@code mjpeg} simulations.
     */
    @Bean
    public SimulationService simulationService(AssetService assetService,
                                                CategoryRepositoryPort categoryRepositoryPort,
                                                FeedTransmitterRegistry feedTransmitterRegistry,
                                                VisionPublishProperties properties,
                                                VisionApplicationProperties applicationProperties) {
        VisionApplicationProperties.Simulation simulation = applicationProperties.simulation();
        return new DefaultSimulationService(assetService, categoryRepositoryPort, feedTransmitterRegistry,
                properties.mediamtx().rtspBase(),
                new SimulationServiceSettings(simulation.mavlinkLoopbackHost(), simulation.fallbackLatitude(),
                        simulation.fallbackLongitude()));
    }

    /**
     * Simulated-feed resume-on-boot: restarts the TX feed for every persisted simulated asset
     * whose {@code rtsp} video device is one of this app's own TX-fed feeds. {@code enabled} is
     * resolved once, here, from both gates: {@link VisionPersistenceProperties#enabled()}
     * <em>and</em> {@link VisionSimulationProperties#resumeOnBoot()} (default {@code true}).
     */
    @Bean
    public ApplicationRunner simulationResumeRunner(SimulationService simulationService,
                                                     VisionPersistenceProperties persistenceProperties,
                                                     VisionSimulationProperties simulationProperties) {
        return new SimulationResumeRunner(simulationService,
                persistenceProperties.enabled() && simulationProperties.resumeOnBoot());
    }
}

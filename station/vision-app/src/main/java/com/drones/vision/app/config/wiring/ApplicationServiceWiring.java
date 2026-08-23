package com.drones.vision.app.config.wiring;

import com.drones.vision.perception.domain.port.DetectionDemandPort;
import com.drones.vision.perception.domain.port.DetectionEventRepositoryPort;
import com.drones.vision.perception.domain.port.DetectionLiveUpdatePort;
import com.drones.vision.perception.domain.port.DetectionRepositoryPort;
import com.drones.vision.platform.EventLiveUpdatePort;
import com.drones.vision.platform.EventPublisherPort;
import com.drones.vision.warehouse.domain.port.AssetLiveStatePort;
import com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort;
import com.drones.vision.flight.domain.port.FlightCommandPort;
import com.drones.vision.flight.domain.port.GeofenceRepositoryPort;
import com.drones.vision.flight.domain.port.ManualControlPort;
import com.drones.vision.flight.domain.port.TelemetryLiveUpdatePort;
import com.drones.vision.flight.domain.port.TelemetryRepositoryPort;
import com.drones.vision.flight.domain.port.TelemetrySourcePort;
import com.drones.vision.flight.domain.port.TrackCorrectionLiveUpdatePort;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.map.domain.port.DrawingRepositoryPort;
import com.drones.vision.map.domain.port.MapLayerRepositoryPort;
import com.drones.vision.map.domain.port.MapLiveUpdatePort;
import com.drones.vision.map.domain.port.MarkRepositoryPort;
import com.drones.vision.perception.domain.port.DetectionPort;
import com.drones.vision.perception.domain.port.StreamPublisherPort;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import com.drones.vision.warehouse.domain.port.CategoryRepositoryPort;
import com.drones.vision.warehouse.domain.port.DeviceRepositoryPort;
import com.drones.vision.warehouse.domain.port.FleetLiveUpdatePort;
import com.drones.vision.adapter.cvgrpc.GrpcDetectionPort;
import com.drones.vision.adapter.persistence.repository.JpaAuditTrail;
import com.drones.vision.adapter.persistence.repository.JpaDetectionEventRepository;
import com.drones.vision.adapter.publishhls.MediamtxLiveFrameGrabber;
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
import com.drones.vision.app.stream.LiveFrameFallbackStreamService;
import com.drones.vision.perception.domain.port.PulledDetectionPort;
import com.drones.vision.warehouse.application.asset.*;
import com.drones.vision.warehouse.application.category.*;
import com.drones.vision.warehouse.application.device.*;
import com.drones.vision.warehouse.application.fleet.*;
import com.drones.vision.flight.application.*;
import com.drones.vision.flight.application.geofence.*;
import com.drones.vision.map.application.*;
import com.drones.vision.map.application.mark.*;
import com.drones.vision.perception.application.device.*;
import com.drones.vision.perception.application.pipeline.*;
import com.drones.vision.events.application.*;
import com.drones.vision.simulation.application.*;
import com.drones.vision.perception.application.stream.*;
import com.drones.vision.warehouse.application.usage.*;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.persistence.EntityManagerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Wires the {@code vision-application} service layer — the largest slice of what used to be one
 * 825-line {@code WiringConfiguration} (docs/plans/active/LAYERING-REFACTOR-PLAN.md wave D): every {@code
 * DefaultXService}, the event/audit/live-update decorator chains, and {@link
 * #simulationResumeRunner}. This is the only place in the codebase allowed to know about both the
 * application layer and concrete adapters/devsupport fallbacks for these ports — enforced by
 * {@code ArchitectureTest}.
 *
 * <p>Every repository-shaped port is Postgres-backed via {@code adapter-persistence} since
 * docs/plans/done/POSTGRES-ONLY-CONTEXT.md W2b removed the last two in-memory-only ones ({@link
 * #auditTrailPort}/{@link #detectionEventRepositoryPort}); ports that back a genuinely optional
 * feature (CV detection, stream publishing, replay extraction) still fall back to an in-process
 * no-op when that feature is switched off — see {@code devsupport}'s remaining classes. The live
 * server-push data plane (docs/plans/done/REALTIME-PLAN.md
 * §4): {@link #fleetLiveUpdatePort}/{@link #telemetryLiveUpdatePort}/{@link
 * #detectionLiveUpdatePort}/{@link #mapLiveUpdatePort}/{@link #eventLiveUpdatePort}/{@link
 * #trackCorrectionLiveUpdatePort} each select between the real {@code LiveUpdateRegistry}
 * (vision-api, which implements all six — five ports the former god-port {@code
 * LiveUpdatePublisherPort} split into, docs/plans/active/DOMAIN-SEPARATION-W1.md §15, W1.6b, plus a
 * sixth added for visual geolocation's {@code geo:<assetId>} topic,
 * docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.4/D11/H5) and {@code NoopLiveUpdatePublisher} (same
 * six-interface shape) per {@link VisionLiveProperties#enabled()} (default {@code true}); when
 * enabled, every one of the six bean methods resolves to the same {@code LiveUpdateRegistry}
 * singleton, so a call through any one port still lands on the one shared dispatcher. {@link
 * #telemetryLiveUpdatePort}/{@link #detectionLiveUpdatePort} are threaded unconditionally into
 * {@link #usageTracker}/{@link #streamService}; {@link #auditTrailPort}/{@link
 * #eventPublisherPort}/{@link #detectionEventRepositoryPort} each gain one more decorator ({@link
 * LiveUpdateAuditTrail}/{@link LiveUpdateEventPublisher}/{@link LiveUpdateDetectionEventRepository})
 * only when that property is {@code true}.
 */
@Configuration
@EnableConfigurationProperties({VisionCvProperties.class, VisionLiveProperties.class, VisionRcProperties.class,
        VisionApplicationProperties.class, VisionPublishProperties.class, VisionSimulationProperties.class})
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
                                                  EventLiveUpdatePort eventLiveUpdatePort,
                                                  FleetLiveUpdatePort fleetLiveUpdatePort,
                                                  VisionLiveProperties liveProperties) {
        EventPublisherPort delegate = new LoggingEventPublisher();
        if (cvProperties.enabled() && detectionPort instanceof GrpcDetectionPort grpcDetectionPort) {
            delegate = new DetectionSessionCleanupEventPublisher(delegate, grpcDetectionPort);
        }
        if (liveProperties.enabled()) {
            delegate = new LiveUpdateEventPublisher(delegate, eventLiveUpdatePort, fleetLiveUpdatePort);
        }
        return delegate;
    }

    /**
     * Selects the {@link FleetLiveUpdatePort} implementation per {@link
     * VisionLiveProperties#enabled()} (docs/plans/done/REALTIME-PLAN.md §4, item 4): {@code true} (the
     * default) wires the real {@code LiveUpdateRegistry} (vision-api, component-scanned); {@code
     * false} wires {@link NoopLiveUpdatePublisher}. One of five near-identical selector methods —
     * see this class's own javadoc for why there are five rather than one.
     *
     * <p>{@code @Qualifier("liveUpdateRegistry")} on every one of the five methods' {@code registry}
     * parameter is load-bearing, not decorative: once any one of the five has resolved and cached
     * its bean (say {@code fleetLiveUpdatePort}), that bean's actual runtime type <em>is</em> {@code
     * LiveUpdateRegistry}, so a later, unqualified {@code ObjectProvider<LiveUpdateRegistry>}
     * lookup from a sibling method sees <b>two</b> candidates — the real component-scanned bean and
     * the already-created sibling — and throws {@code NoSuchBeanDefinitionException} ("expected
     * single matching bean but found 2"). This never happened with the one former
     * {@code liveUpdatePublisherPort} bean (nothing else ever looked up {@code LiveUpdateRegistry}
     * by type after it), but five near-identical methods make the collision real between them.
     */
    @Bean
    public FleetLiveUpdatePort fleetLiveUpdatePort(VisionLiveProperties properties,
                                                    @Qualifier("liveUpdateRegistry") ObjectProvider<LiveUpdateRegistry> registry) {
        if (properties.enabled()) {
            return registry.getObject();
        }
        return new NoopLiveUpdatePublisher();
    }

    /** Selects the {@link TelemetryLiveUpdatePort} implementation — see {@link #fleetLiveUpdatePort}. */
    @Bean
    public TelemetryLiveUpdatePort telemetryLiveUpdatePort(VisionLiveProperties properties,
                                                            @Qualifier("liveUpdateRegistry") ObjectProvider<LiveUpdateRegistry> registry) {
        if (properties.enabled()) {
            return registry.getObject();
        }
        return new NoopLiveUpdatePublisher();
    }

    /** Selects the {@link DetectionLiveUpdatePort} implementation — see {@link #fleetLiveUpdatePort}. */
    @Bean
    public DetectionLiveUpdatePort detectionLiveUpdatePort(VisionLiveProperties properties,
                                                            @Qualifier("liveUpdateRegistry") ObjectProvider<LiveUpdateRegistry> registry) {
        if (properties.enabled()) {
            return registry.getObject();
        }
        return new NoopLiveUpdatePublisher();
    }

    /** Selects the {@link MapLiveUpdatePort} implementation — see {@link #fleetLiveUpdatePort}. */
    @Bean
    public MapLiveUpdatePort mapLiveUpdatePort(VisionLiveProperties properties,
                                                @Qualifier("liveUpdateRegistry") ObjectProvider<LiveUpdateRegistry> registry) {
        if (properties.enabled()) {
            return registry.getObject();
        }
        return new NoopLiveUpdatePublisher();
    }

    /** Selects the {@link EventLiveUpdatePort} implementation — see {@link #fleetLiveUpdatePort}. */
    @Bean
    public EventLiveUpdatePort eventLiveUpdatePort(VisionLiveProperties properties,
                                                    @Qualifier("liveUpdateRegistry") ObjectProvider<LiveUpdateRegistry> registry) {
        if (properties.enabled()) {
            return registry.getObject();
        }
        return new NoopLiveUpdatePublisher();
    }

    /**
     * Selects the {@link TrackCorrectionLiveUpdatePort} implementation — see {@link
     * #fleetLiveUpdatePort}; the sixth selector, added for visual geolocation's {@code
     * geo:<assetId>} topic (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.4/D11/H5), gated on the same
     * {@link VisionLiveProperties#enabled()} flag as the other five rather than a new one — {@code
     * vision.geo.visual.enabled} decides whether the feature runs at all; whether its SSE topic
     * exists is the live registry's own concern.
     */
    @Bean
    public TrackCorrectionLiveUpdatePort trackCorrectionLiveUpdatePort(VisionLiveProperties properties,
                                                    @Qualifier("liveUpdateRegistry") ObjectProvider<LiveUpdateRegistry> registry) {
        if (properties.enabled()) {
            return registry.getObject();
        }
        return new NoopLiveUpdatePublisher();
    }

    /**
     * The stateful, watchdog-supervised RC-relay session service (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md
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
                                        AssetLiveStatePort assetLiveStatePort,
                                        AuditTrailPort auditTrailPort,
                                        EventPublisherPort eventPublisherPort) {
        return new DefaultDeviceService(deviceRepositoryPort, assetLiveStatePort, auditTrailPort, eventPublisherPort);
    }

    /**
     * Append-only record of who changed the fleet — Postgres-backed via {@link JpaAuditTrail}
     * (docs/plans/done/POSTGRES-ONLY-CONTEXT.md W3/W2b: the durable replacement for the old
     * {@code InMemoryAuditTrail}, whose own javadoc called out that an audit trail evaporating on
     * restart was not one). When {@link VisionLiveProperties#enabled()} is {@code true}, wrapped in
     * {@link LiveUpdateAuditTrail}, which announces a "fleet changed" live update for every
     * recorded entry.
     */
    @Bean
    public AuditTrailPort auditTrailPort(FleetLiveUpdatePort fleetLiveUpdatePort,
                                          VisionLiveProperties liveProperties,
                                          EntityManagerFactory entityManagerFactory) {
        AuditTrailPort delegate = new JpaAuditTrail(entityManagerFactory);
        if (liveProperties.enabled()) {
            return new LiveUpdateAuditTrail(delegate, fleetLiveUpdatePort);
        }
        return delegate;
    }

    /**
     * Debounced detection events (docs/plans/done/MVP2-PLAN.md §E, E-a) — Postgres-backed via {@link
     * JpaDetectionEventRepository} (docs/plans/done/POSTGRES-ONLY-CONTEXT.md W3/W2b, replacing the
     * old {@code InMemoryDetectionEventRepository}). When {@link VisionLiveProperties#enabled()} is
     * {@code true}, wrapped in {@link LiveUpdateDetectionEventRepository}, which announces every
     * {@code save} as a live update.
     */
    @Bean
    public DetectionEventRepositoryPort detectionEventRepositoryPort(DetectionLiveUpdatePort detectionLiveUpdatePort,
                                                                       VisionLiveProperties liveProperties,
                                                                       EntityManagerFactory entityManagerFactory) {
        DetectionEventRepositoryPort delegate = new JpaDetectionEventRepository(entityManagerFactory);
        if (liveProperties.enabled()) {
            return new LiveUpdateDetectionEventRepository(delegate, detectionLiveUpdatePort);
        }
        return delegate;
    }

    /**
     * Evaluates live telemetry against the enabled {@code GeofenceZone} set and raises {@code
     * GEOFENCE_BREACH} events on edge transitions (docs/plans/done/OPS-CORE-PLAN.md §G) — threaded into
     * {@link #usageTracker} below as a nullable-by-convention but always-real collaborator.
     */
    @Bean
    public GeofenceMonitor geofenceMonitor(GeofenceRepositoryPort geofenceRepositoryPort,
                                            EventPublisherPort eventPublisherPort,
                                            EventLiveUpdatePort eventLiveUpdatePort) {
        return new GeofenceMonitor(geofenceRepositoryPort, eventPublisherPort, eventLiveUpdatePort);
    }

    /**
     * CRUD/list over geofence zones (docs/plans/done/OPS-CORE-PLAN.md §G) behind {@code GeofenceController}
     * (vision-api, component-scanned) — a one-line assembly, mirroring {@link #replayService}'s
     * shape.
     */
    @Bean
    public GeofenceService geofenceService(GeofenceRepositoryPort geofenceRepositoryPort,
                                            GeofenceMonitor geofenceMonitor) {
        return new DefaultGeofenceService(geofenceRepositoryPort, geofenceMonitor);
    }

    /**
     * Drives {@link com.drones.vision.warehouse.domain.model.AssetUsage} lifecycle and telemetry sampling
     * from {@link StreamService}'s start/stop notifications. {@code telemetryLiveUpdatePort} and
     * {@code geofenceMonitor} are threaded through unconditionally — both are always real beans.
     *
     * <p>Takes its summary-coalescing window from {@code vision.persistence.telemetry} — the same
     * block {@code telemetryRepositoryPort} reads (docs/plans/done/SCALE-100-PLAN.md S4). The two
     * writes this class makes per sample are one ingest decision, so they are configured by one
     * number even though they need two settings types to cross the module boundary.
     *
     * <p>{@code usagePhaseObserver} (docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.4, Wave O11) is
     * an {@link ObjectProvider} because {@code OnboardingWiringConfiguration#passportCaptureObserver}
     * is itself conditionally present on {@code vision.onboarding.passport.enabled} (default {@code
     * false}) — resolving to {@link UsagePhaseObserver#NOOP} when that flag is off reproduces the
     * pre-O11 constructor's behaviour exactly: every phase fold still happens, nothing is ever
     * notified. Same defaulting idiom as {@link #streamService}'s {@code detectionDemandPort}.
     * {@code phaseSettings} stays {@link UsagePhaseSettings#defaults()} exactly as it was before this
     * bean took an observer at all — nothing in this module binds {@code vision.flight.phase.*} yet.
     */
    @Bean
    public UsageTracker usageTracker(AssetRepositoryPort assetRepositoryPort,
                                      DeviceRepositoryPort deviceRepositoryPort,
                                      AssetUsageRepositoryPort assetUsageRepositoryPort,
                                      TelemetryRepositoryPort telemetryRepositoryPort,
                                      List<TelemetrySourcePort> telemetrySources,
                                      TelemetryLiveUpdatePort telemetryLiveUpdatePort,
                                      GeofenceMonitor geofenceMonitor,
                                      VisionPersistenceProperties persistenceProperties,
                                      ObjectProvider<UsagePhaseObserver> usagePhaseObserver) {
        // geofenceMonitor::evaluate, not the monitor itself: UsageTracker (perception) takes a
        // BiConsumer seam so it never depends on the flight context — docs/plans/active/DOMAIN-SEPARATION-W1.md §5 C2
        return new UsageTracker(assetRepositoryPort, deviceRepositoryPort, assetUsageRepositoryPort,
                telemetryRepositoryPort, telemetrySources, telemetryLiveUpdatePort, geofenceMonitor::evaluate,
                persistenceProperties.telemetry().toSummarySettings(), UsagePhaseSettings.defaults(),
                usagePhaseObserver.getIfAvailable(() -> UsagePhaseObserver.NOOP));
    }

    /**
     * The map's authorization model (docs/plans/done/MAP-REWORK-PLAN.md §3) — pure, stateless, no ports, so one
     * shared singleton serves every map service and {@code CurrentUser#viewer()}'s consumers alike.
     */
    @Bean
    public MapAccessPolicy mapAccessPolicy() {
        return new MapAccessPolicy();
    }

    /**
     * The shared layer-lookup/default-layer collaborator every map service composes
     * (docs/plans/done/MAP-REWORK-PLAN.md §3) — deliberately one bean rather than three instances, because its
     * {@code copLayerId()}/{@code defaultLayerFor()} are {@code synchronized} find-or-create methods
     * whose idempotence depends on a single instance guarding a single repository.
     */
    @Bean
    public LayerResolver layerResolver(MapLayerRepositoryPort mapLayerRepositoryPort,
                                        MapLiveUpdatePort mapLiveUpdatePort) {
        return new LayerResolver(mapLayerRepositoryPort, mapLiveUpdatePort);
    }

    /**
     * Layer CRUD + grants (docs/plans/done/MAP-REWORK-PLAN.md §3) behind {@code MapLayersController}
     * (vision-api, component-scanned). Takes the mark/drawing repositories directly — not their
     * services — because the only thing it does with them is cascade a layer deletion, for which the
     * services' own viewer-gated methods would be both wrong (the cascade is already authorized) and
     * circular.
     */
    @Bean
    public MapLayerService mapLayerService(LayerResolver layerResolver, MarkRepositoryPort markRepositoryPort,
                                            DrawingRepositoryPort drawingRepositoryPort,
                                            MapLiveUpdatePort mapLiveUpdatePort,
                                            MapAccessPolicy mapAccessPolicy) {
        return new DefaultMapLayerService(layerResolver, markRepositoryPort, drawingRepositoryPort,
                mapLiveUpdatePort, mapAccessPolicy);
    }

    /**
     * The tactical marks half of the common operational picture (docs/plans/done/MAP-REWORK-PLAN.md §3) behind
     * {@code MapMarksController} (vision-api, component-scanned) — reworked in place from the
     * docs/plans/done/TACTICAL-MARKS-PLAN.md M4 bean this replaces, which took no policy and no layer resolver.
     * {@code usageTracker} still backs the cockpit "geolocate" action ({@code
     * UsageTracker#latestTelemetry}); {@code mapLiveUpdatePort} is always a real bean, so every
     * mutation is announced on the {@code map} SSE topic unconditionally.
     */
    @Bean
    public MarkService markService(MarkRepositoryPort markRepositoryPort, UsageTracker usageTracker,
                                    MapLiveUpdatePort mapLiveUpdatePort,
                                    MapAccessPolicy mapAccessPolicy, LayerResolver layerResolver) {
        return new DefaultMarkService(markRepositoryPort, usageTracker, mapLiveUpdatePort,
                mapAccessPolicy, layerResolver);
    }

    /**
     * Lines/polygons/arrows/text on the map (docs/plans/done/MAP-REWORK-PLAN.md §3) behind {@code
     * MapDrawingsController} (vision-api, component-scanned) — a one-line assembly, mirroring
     * {@link #markService}'s shape minus the telemetry collaborator a drawing has no use for.
     */
    @Bean
    public DrawingService drawingService(DrawingRepositoryPort drawingRepositoryPort,
                                          MapLiveUpdatePort mapLiveUpdatePort,
                                          MapAccessPolicy mapAccessPolicy, LayerResolver layerResolver) {
        return new DefaultDrawingService(drawingRepositoryPort, mapLiveUpdatePort, mapAccessPolicy,
                layerResolver);
    }

    /**
     * {@code detectionLiveUpdatePort} is threaded through unconditionally, same reasoning as {@link
     * #usageTracker} above — every stream pipeline this service starts announces its completed
     * detection results regardless of {@link VisionLiveProperties#enabled()}.
     *
     * <h2>docs/plans/done/MEDIA-SOT-PLAN.md wave M7 — switch B and the live-frame fallback</h2>
     * {@code pullDetectionSettings} is built here, not injected, because it is a composite of two
     * things this method already has separate access to: {@link VisionCvProperties#pull()}'s {@code
     * rtsp-base} (the address the <b>worker</b> dials, D5) and {@code CvWiring}'s conditionally-present
     * {@code pulledDetectionPort} bean (absent unless {@link VisionCvProperties#pullEnabled()}). {@code
     * null} (the default, {@code frame-transport=push}) reproduces the pre-wave-M5 11-arg constructor's
     * behaviour exactly — see {@code DefaultStreamService}'s own javadoc on that parameter.
     *
     * <p>The returned {@link StreamService} is wrapped in {@code
     * com.drones.vision.app.stream.LiveFrameFallbackStreamService} only when {@link
     * VisionPublishProperties.SourceProxy#enabled()} is {@code true} — the one condition under which a
     * running stream's pipeline cache can be permanently empty (D4: a proxied stream opens no {@code
     * VideoSourcePort} at all). With that flag at its default {@code false} (D1), this method returns
     * the plain {@code DefaultStreamService} exactly as before this wave, so {@code
     * LiveFrameFallbackStreamService} is never even constructed in the default configuration.
     *
     * <p>{@code detectionDemandPort} (docs/plans/done/CV-DEMAND-PLAN.md §3.3) is an {@link
     * ObjectProvider} because {@code CvWiring#detectionDemandPort} is itself conditionally present
     * on {@code vision.cv.demand.enabled} (default {@code true}) — resolving to {@code null} when
     * that flag is {@code false} reproduces {@code DefaultStreamService}'s pre-wave-D2 constructor
     * exactly: the demand-poll task is never scheduled, and every stream stays fail-open on demand.
     */
    @Bean
    public StreamService streamService(DeviceRepositoryPort deviceRepositoryPort,
                                        VideoSourceRegistry videoSourceRegistry,
                                        DetectionPort detectionPort,
                                        StreamPublisherPort streamPublisherPort,
                                        DetectionRepositoryPort detectionRepositoryPort,
                                        EventPublisherPort eventPublisherPort,
                                        UsageTracker usageTracker,
                                        DetectionEventRepositoryPort detectionEventRepositoryPort,
                                        DetectionLiveUpdatePort detectionLiveUpdatePort,
                                        VisionApplicationProperties applicationProperties,
                                        VisionTrackingProperties trackingProperties,
                                        VisionCvProperties cvProperties,
                                        VisionPublishProperties publishProperties,
                                        ObjectProvider<PulledDetectionPort> pulledDetectionPort,
                                        MediamtxLiveFrameGrabber mediamtxLiveFrameGrabber,
                                        ObjectProvider<DetectionDemandPort> detectionDemandPort) {
        PullDetectionSettings pullDetectionSettings = cvProperties.pullEnabled()
                ? new PullDetectionSettings(pulledDetectionPort.getObject(), cvProperties.pull().rtspBase())
                : null;
        StreamService defaultStreamService = new DefaultStreamService(deviceRepositoryPort, videoSourceRegistry,
                detectionPort, streamPublisherPort, detectionRepositoryPort, eventPublisherPort, usageTracker,
                detectionEventRepositoryPort, detectionLiveUpdatePort,
                streamPipelineSettings(applicationProperties, trackingProperties, cvProperties), pullDetectionSettings,
                detectionDemandPort.getIfAvailable());
        if (publishProperties.sourceProxy().enabled()) {
            return new LiveFrameFallbackStreamService(defaultStreamService, mediamtxLiveFrameGrabber);
        }
        return defaultStreamService;
    }

    /**
     * Maps {@link VisionApplicationProperties.Pipeline}/{@link VisionApplicationProperties.Extrapolation}
     * and {@link VisionTrackingProperties}' two read-model windows onto {@code
     * com.drones.vision.perception.application.pipeline.StreamPipelineSettings} — the two backoff
     * pairs are bound in milliseconds but the settings record's own unit is nanoseconds, so this is
     * where the conversion happens, once.
     *
     * <p>The tracking window pair comes from {@code vision.tracking.*} rather than {@code
     * vision.application.*} (docs/extracts/TRACKING-ORCHESTRATION.md &sect;4.3): {@code stats-window-seconds}
     * is how far back {@code TrackingStatsWindow}'s duty-cycle counters reach — the number {@code GET
     * /api/streams/{id}/tracks} reports as {@code stats.windowSeconds} — and {@code
     * track-retention-seconds} is how long {@code TrackBook} keeps a track that stopped arriving.
     * Both configure per-stream <b>read models</b>, so unlike the mode/cadence seeds they apply to
     * every stream this instance starts from then on. Every default is byte-identical to the literal
     * the settings record's own convenience constructor uses.
     *
     * <p>The mode/cadence seeds ({@code default-mode}, {@code verify-every-millis}, {@code
     * follow-fps}) ride the same record as {@link StreamPipelineSettings#trackingSeed()}, mapped by
     * {@link TrackingWiring#streamStartTrackingSeed}. They seed <b>new</b> streams only, applied at
     * {@code DefaultStreamService#start} — the one point every start path passes through, so the
     * device, asset, simulation and demo-fleet paths cannot disagree about them
     * (docs/extracts/TRACKING-ORCHESTRATION.md &sect;4.1).
     *
     * <p>{@code cvProperties.demand()} (docs/plans/done/CV-DEMAND-PLAN.md &sect;3.4/&sect;3.7)
     * supplies {@link StreamPipelineSettings#detectionDemandPollInterval()}/{@link
     * StreamPipelineSettings#detectionDemandGrace()} — the two tunables that only matter once {@link
     * #streamService} has actually wired a {@code DetectionDemandPort}, but are threaded through
     * unconditionally since {@link StreamPipelineSettings}'s own compact constructor requires both
     * regardless.
     *
     * <p>{@code pipeline.videoStaleAfter()} (docs/plans/done/STREAM-STATE-PLAN.md &sect;2.4) supplies
     * {@link StreamPipelineSettings#videoStaleAfter()} — how long a stream may go without a frame
     * before its {@code StreamState} reads {@code STALLED}. It sits with the other per-stream
     * read-model tunables here rather than under a lifecycle root, because that is what it is: a
     * threshold over the pipeline's own frame cadence.
     */
    static StreamPipelineSettings streamPipelineSettings(VisionApplicationProperties properties,
                                                          VisionTrackingProperties tracking,
                                                          VisionCvProperties cvProperties) {
        VisionApplicationProperties.Pipeline pipeline = properties.pipeline();
        VisionApplicationProperties.Extrapolation extrapolation = properties.extrapolation();
        VisionCvProperties.Demand demand = cvProperties.demand();
        return new StreamPipelineSettings(pipeline.assumedSourceFps(), pipeline.measuredFpsEwmaAlpha(),
                pipeline.warmupFrames(), pipeline.minMeasuredFps(), pipeline.maxMeasuredFps(),
                TimeUnit.MILLISECONDS.toNanos(pipeline.detectionBackoff().initialMs()),
                TimeUnit.MILLISECONDS.toNanos(pipeline.detectionBackoff().maxMs()),
                TimeUnit.MILLISECONDS.toNanos(pipeline.sourceReopenBackoff().initialMs()),
                TimeUnit.MILLISECONDS.toNanos(pipeline.sourceReopenBackoff().maxMs()),
                extrapolation.maxMillis(), extrapolation.matchGate(),
                Duration.ofSeconds(tracking.statsWindowSeconds()),
                Duration.ofSeconds(tracking.trackRetentionSeconds()),
                TrackingWiring.streamStartTrackingSeed(tracking),
                pipeline.cameraHfovDegrees(),
                new AdaptiveRateSettings(pipeline.adaptiveRate().enabled(), pipeline.adaptiveRate().maxFps(),
                        pipeline.adaptiveRate().ewmaAlpha()),
                demand.pollInterval(), demand.grace(), pipeline.videoStaleAfter());
    }

    /**
     * Warehouse's declared read of live runtime state (docs/plans/active/DOMAIN-SEPARATION-W1.md
     * &sect;15, W1.6e) — {@link AssetLiveStatePort} is a warehouse-owned interface, implemented
     * here by {@code StreamBackedAssetLiveState}, the one class in perception allowed to compose
     * {@link StreamService}, {@link UsageTracker} and {@link DetectionEventRepositoryPort} to
     * answer it. Every warehouse service that used to depend on those three runtime collaborators
     * directly ({@link #assetService}, {@link #deviceService}, {@link #assetStatsService}, {@link
     * #fleetSummaryService}) now depends on this one port instead.
     */
    @Bean
    public AssetLiveStatePort assetLiveStatePort(StreamService streamService, UsageTracker usageTracker,
                                                  DetectionEventRepositoryPort detectionEventRepositoryPort) {
        return new StreamBackedAssetLiveState(streamService, usageTracker, detectionEventRepositoryPort);
    }

    /**
     * Asset-level streaming (docs/plans/active/DOMAIN-SEPARATION-W1.md &sect;15, W1.6e): resolves
     * which of an asset's devices to use, then calls {@link StreamService} directly — split off
     * {@link #assetService} because starting a stream hands perception's own configuration types
     * ({@code PipelineConfig}/{@code TrackingConfigPatch}) to the runtime, which is perception's
     * job, not inventory's. {@code perception -> warehouse} is the legal direction, so this
     * resolves the asset itself rather than calling back into {@link AssetService}.
     */
    @Bean
    public AssetStreamService assetStreamService(AssetRepositoryPort assetRepositoryPort,
                                                  DeviceService deviceService,
                                                  StreamService streamService) {
        return new DefaultAssetStreamService(assetRepositoryPort, deviceService, streamService);
    }

    /**
     * Assets — creation, editing, lifecycle, and stopping a stream. Devices are reached through
     * {@link DeviceService} rather than the repository, so rules that belong to a source live in
     * exactly one place. Starting a stream lives on {@link #assetStreamService} instead — see its
     * own javadoc.
     */
    @Bean
    public AssetService assetService(AssetRepositoryPort assetRepositoryPort,
                                      CategoryRepositoryPort categoryRepositoryPort,
                                      AssetUsageRepositoryPort assetUsageRepositoryPort,
                                      AuditTrailPort auditTrailPort,
                                      DeviceService deviceService,
                                      AssetLiveStatePort assetLiveStatePort) {
        return new DefaultAssetService(assetRepositoryPort, categoryRepositoryPort, assetUsageRepositoryPort,
                auditTrailPort, deviceService, assetLiveStatePort);
    }

    @Bean
    public CategoryService categoryService(CategoryRepositoryPort categoryRepositoryPort) {
        return new DefaultCategoryService(categoryRepositoryPort);
    }

    /**
     * Guarded flight command TX (docs/plans/active/DRONE-INFRA-PLAN.md I-e, Stage 1 — "bring it home"): the
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
     * Flight replay (docs/plans/done/MVP2-PLAN.md §R, R-a/R-a2) plus its recording/clip-export read
     * (docs/plans/done/OPS-CORE-PLAN.md §R, R-b): the read side behind {@code UsageTimelineController}
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
     * The fleet-wide "replay library" list (docs/plans/done/NAV-IA-REDESIGN-PLAN.md Wave 4, F8): the read side
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
     * The manager dashboard's single aggregated read (docs/plans/done/MVP3-PLAN.md C-a): the read side behind
     * {@code FleetController} (vision-api, component-scanned).
     */
    @Bean
    public FleetSummaryService fleetSummaryService(AssetService assetService,
                                                     AssetLiveStatePort assetLiveStatePort,
                                                     VisionApplicationProperties applicationProperties) {
        VisionApplicationProperties.Fleet fleet = applicationProperties.fleet();
        return new DefaultFleetSummaryService(assetService, assetLiveStatePort, fleet.maxAssets(),
                fleet.openEventsScanLimit());
    }

    /**
     * One asset's flight-utilization stats (docs/plans/done/ASSET-MANAGER-PAGE-PLAN.md, Wave A): the read
     * side behind {@code AssetStatsController}'s {@code GET /api/assets/{id}/stats} (vision-api,
     * component-scanned).
     */
    @Bean
    public AssetStatsService assetStatsService(AssetUsageRepositoryPort assetUsageRepositoryPort,
                                                AssetLiveStatePort assetLiveStatePort,
                                                VisionApplicationProperties applicationProperties) {
        return new DefaultAssetStatsService(assetUsageRepositoryPort, assetLiveStatePort,
                applicationProperties.stats().fetchLimit());
    }

    /**
     * Test-before-save connection probe (docs/plans/done/UX-REWORK-PLAN.md §U-d item 3, UX-DESIGN.md §5.1):
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
     * The one-call, zero-hardware simulation entry point (docs/main/CYCLES-PLAN.md §1b, §3, §5): turns
     * a video file path into a registered {@code simulated}-category asset via {@link
     * #assetService}, reusing every rule it already enforces. {@code feedTransmitterRegistry}
     * (from {@code FeedTransmitterWiring}) backs {@code transport=rtsp}/{@code mjpeg} simulations.
     */
    @Bean
    public SimulationService simulationService(AssetService assetService,
                                                AssetStreamService assetStreamService,
                                                CategoryRepositoryPort categoryRepositoryPort,
                                                FeedTransmitterRegistry feedTransmitterRegistry,
                                                VisionPublishProperties properties,
                                                VisionApplicationProperties applicationProperties) {
        VisionApplicationProperties.Simulation simulation = applicationProperties.simulation();
        return new DefaultSimulationService(assetService, assetStreamService, categoryRepositoryPort,
                feedTransmitterRegistry, properties.mediamtx().rtspBase(),
                new SimulationServiceSettings(simulation.mavlinkLoopbackHost(), simulation.fallbackLatitude(),
                        simulation.fallbackLongitude()));
    }

    /**
     * Simulated-feed resume-on-boot: restarts the TX feed for every persisted simulated asset
     * whose {@code rtsp} video device is one of this app's own TX-fed feeds. {@code enabled} is
     * resolved once, here, from {@link VisionSimulationProperties#resumeOnBoot()} (default {@code
     * true}) — the former {@code VisionPersistenceProperties#enabled()} half of this gate is gone
     * along with the flag itself (docs/plans/done/POSTGRES-ONLY-CONTEXT.md W2b: Postgres is the
     * only store now, so there is always something to resume from).
     */
    @Bean
    public ApplicationRunner simulationResumeRunner(SimulationService simulationService,
                                                     VisionSimulationProperties simulationProperties) {
        return new SimulationResumeRunner(simulationService, simulationProperties.resumeOnBoot());
    }
}

package com.drones.vision.app;

import com.drones.vision.adapter.cvgrpc.GrpcDetectionPort;
import com.drones.vision.adapter.mavlink.MavlinkFeedTransmitter;
import com.drones.vision.adapter.mavlink.MavlinkFlightCommander;
import com.drones.vision.adapter.mavlink.MavlinkTelemetrySource;
import com.drones.vision.adapter.mjpeg.MjpegFeedTransmitter;
import com.drones.vision.adapter.mjpeg.MjpegVideoSource;
import com.drones.vision.adapter.overlay.Java2DOverlayRenderer;
import com.drones.vision.adapter.publishhls.MediamtxStreamPublisher;
import com.drones.vision.adapter.rtsp.FfmpegVideoSource;
import com.drones.vision.adapter.rtsp.RtspFeedTransmitter;
import com.drones.vision.adapter.simulation.SimulatedTelemetrySource;
import com.drones.vision.adapter.simulation.SimulatedVideoSource;
import com.drones.vision.adapter.v4l2.V4l2VideoSource;
import com.drones.vision.api.HlsProxyController;
import com.drones.vision.api.dto.CvModelResponse;
import com.drones.vision.api.live.LiveUpdateRegistry;
import com.drones.vision.app.devsupport.InMemoryAuditTrail;
import com.drones.vision.app.devsupport.InMemoryDetectionEventRepository;
import com.drones.vision.app.devsupport.LoggingEventPublisher;
import com.drones.vision.app.devsupport.NoopDetectionPort;
import com.drones.vision.app.devsupport.NoopLiveUpdatePublisher;
import com.drones.vision.app.devsupport.NoopStreamPublisher;
import com.drones.vision.application.AssetService;
import com.drones.vision.application.AssetStatsService;
import com.drones.vision.application.DefaultAssetService;
import com.drones.vision.application.DefaultAssetStatsService;
import com.drones.vision.application.DefaultCategoryService;
import com.drones.vision.application.DefaultDeviceService;
import com.drones.vision.application.DefaultFleetSummaryService;
import com.drones.vision.application.DefaultFlightCommandService;
import com.drones.vision.application.DefaultGeofenceService;
import com.drones.vision.application.DefaultProbeService;
import com.drones.vision.application.DefaultReplayService;
import com.drones.vision.application.DefaultSimulationService;
import com.drones.vision.application.DefaultStreamService;
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
import com.drones.vision.application.StreamService;
import com.drones.vision.application.UsageTracker;
import com.drones.vision.application.VideoSourceRegistry;
import com.drones.vision.domain.port.out.AssetRepositoryPort;
import com.drones.vision.domain.port.out.AssetUsageRepositoryPort;
import com.drones.vision.domain.port.out.AuditTrailPort;
import com.drones.vision.domain.port.out.CategoryRepositoryPort;
import com.drones.vision.domain.port.out.DetectionEventRepositoryPort;
import com.drones.vision.domain.port.out.DetectionPort;
import com.drones.vision.domain.port.out.DetectionRepositoryPort;
import com.drones.vision.domain.port.out.DeviceRepositoryPort;
import com.drones.vision.domain.port.out.EventPublisherPort;
import com.drones.vision.domain.port.out.FeedTransmitterPort;
import com.drones.vision.domain.port.out.FlightCommandPort;
import com.drones.vision.domain.port.out.GeofenceRepositoryPort;
import com.drones.vision.domain.port.out.LiveUpdatePublisherPort;
import com.drones.vision.domain.port.out.OverlayPort;
import com.drones.vision.domain.port.out.StreamPublisherPort;
import com.drones.vision.domain.port.out.TelemetryRepositoryPort;
import com.drones.vision.domain.port.out.TelemetrySourcePort;
import com.drones.vision.domain.port.out.VideoSourcePort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.List;

/**
 * Wires the framework-free domain/application layer to adapters.
 *
 * <p>This is the only place in the codebase allowed to know about both the
 * application layer ({@code vision-application}) and concrete adapters
 * ({@code adapter-simulation}, {@code adapter-rtsp}, {@code
 * adapter-publish-hls}, {@code adapter-cv-grpc}, and the dev-support
 * fallbacks in {@link com.drones.vision.app.devsupport}) — enforced by
 * {@code ArchitectureTest}. {@code vision-domain} and {@code
 * vision-application} themselves stay free of Spring annotations; all
 * {@code @Bean}/{@code @Configuration} wiring lives here.
 *
 * <p>Ports that don't yet have a real adapter (event bus) are wired to
 * in-process dev-support fallbacks so the platform runs end to end from
 * Phase 0 onward; each fallback's javadoc names the adapter that will
 * replace it and in which phase. Stream egress ({@link StreamPublisherPort})
 * is real as of Phase 1: {@link #streamPublisherPort(VisionPublishProperties)}
 * selects between the mediamtx-backed publisher and the no-op fallback based
 * on {@link VisionPublishProperties}. CV inference ({@link DetectionPort})
 * is real as of docs/MVP1-PLAN.md §C7: {@link #detectionPort(VisionCvProperties)}
 * selects between {@code GrpcDetectionPort} (adapter-cv-grpc) and the no-op
 * fallback based on {@link VisionCvProperties}; see {@link #eventPublisherPort}
 * for how the gRPC session's per-stream lifecycle is cleaned up, and how that
 * same bean is further wrapped for the server-push data plane below.
 *
 * <p>Fleet persistence (docs/MVP2-PLAN.md P-a: {@code CategoryRepositoryPort}/{@code
 * DeviceRepositoryPort}/{@code AssetRepositoryPort}) and history persistence (docs/MVP2-PLAN.md
 * P-b: {@code AssetUsageRepositoryPort}/{@code TelemetryRepositoryPort}/{@code
 * DetectionRepositoryPort}) are both wired in the sibling {@link PersistenceWiringConfiguration}
 * instead of here — same split-out-by-concern precedent as {@link DiscoveryWiringConfiguration} —
 * selecting between {@code adapter-persistence}'s JPA implementations and the devsupport
 * in-memory fallbacks per {@link VisionPersistenceProperties#enabled()}.
 *
 * <p>The server-push data plane (docs/REALTIME-PLAN.md §4): {@link #liveUpdatePublisherPort}
 * selects between the real {@code LiveUpdateRegistry} (vision-api) and {@code
 * NoopLiveUpdatePublisher} per {@link VisionLiveProperties#enabled()} (default {@code true}),
 * threaded unconditionally into {@link #usageTracker}/{@link #streamService}; {@link
 * #auditTrailPort}/{@link #eventPublisherPort}/{@link #detectionEventRepositoryPort} each gain one
 * more decorator ({@code LiveUpdateAuditTrail}/{@code LiveUpdateEventPublisher}/{@code
 * LiveUpdateDetectionEventRepository}) only when that property is {@code true} — see this module's
 * {@code MODULE.md} "Server-push data plane" section for the full reasoning, including the two
 * topics ({@code devices}, {@code detection-events}) that extend the channel beyond its original
 * R-c scope.
 */
@Configuration
@EnableConfigurationProperties({VisionPublishProperties.class, VisionCvProperties.class, VisionLiveProperties.class,
        VisionSimulationProperties.class})
public class WiringConfiguration {

    @Bean
    public SimulatedVideoSource simulatedVideoSource() {
        return new SimulatedVideoSource();
    }

    @Bean
    public FfmpegVideoSource ffmpegVideoSource() {
        return new FfmpegVideoSource();
    }

    /**
     * RX half of the mjpeg TX/RX pair (docs/CYCLES-PLAN.md §5): ingests the {@code
     * multipart/x-mixed-replace} HTTP stream served by {@link #mjpegFeedTransmitter} (or any real
     * MJPEG camera, e.g. an ESP32-CAM) for {@code "mjpeg"}-protocol video devices.
     */
    @Bean
    public MjpegVideoSource mjpegVideoSource() {
        return new MjpegVideoSource();
    }

    /**
     * USB/V4L2 local camera ingest (docs/MVP2-PLAN.md X-b), RX only (a local capture device has
     * no wire to transmit to -- same as {@code sim}/{@code file}). Supports protocol {@code
     * "v4l2"} with a {@code file:} URI naming a {@code /dev/videoN} node -- the exact shape
     * {@code adapter-discovery}'s {@code V4l2Scanner} emits, not the docs/MVP2-PLAN.md brief's
     * originally-proposed {@code "usb"}/{@code v4l2://} shape; see adapter-v4l2/MODULE.md.
     */
    @Bean
    public V4l2VideoSource v4l2VideoSource() {
        return new V4l2VideoSource();
    }

    @Bean
    public VideoSourceRegistry videoSourceRegistry(List<VideoSourcePort> videoSources) {
        return new VideoSourceRegistry(videoSources);
    }

    /**
     * Burns detection boxes/labels (and, once a telemetry input reaches {@code
     * com.drones.vision.application.StreamPipeline}, a telemetry OSD) onto published frames
     * (docs/MVP1-PLAN.md §C8 bullets 1-2). Stateless, no constructor arguments — see
     * adapter-overlay/MODULE.md for its pass-through-never-throws rules. Threaded into {@link
     * #streamService} below; {@code StreamPipeline}'s own overlay failure handling means a
     * broken/unsupported frame format here degrades to raw frames rather than breaking the video
     * path.
     */
    @Bean
    public OverlayPort overlayRenderer() {
        return new Java2DOverlayRenderer();
    }

    /**
     * The base implementation is always {@link LoggingEventPublisher}. When {@link
     * VisionCvProperties#enabled()} is {@code true} and {@link #detectionPort} resolved to a
     * {@code GrpcDetectionPort} (it always does in that branch — see {@link #detectionPort}), the
     * bean is instead a {@link DetectionSessionCleanupEventPublisher} wrapping it: {@code
     * GrpcDetectionPort} keeps one open gRPC session per stream until told the stream ended, and
     * neither {@code vision-application} nor {@code vision-domain} may reference a concrete
     * adapter to make that call themselves, so this decorator is the wiring-layer seam that
     * forwards a {@code STREAM_STOPPED} event's stream id into {@code
     * GrpcDetectionPort#streamEnded} after delegating the event unchanged. With CV disabled, the
     * decorator is never constructed and this is exactly {@link #eventPublisherPort}'s pre-C7
     * behavior.
     *
     * <p>When {@link VisionLiveProperties#enabled()} is {@code true} (docs/REALTIME-PLAN.md §4),
     * the result (whichever of the above it is) is further wrapped in {@link
     * LiveUpdateEventPublisher}, which announces every event as a live update and, for
     * device/stream lifecycle event types, a "fleet changed" one too — see that class's own
     * javadoc. Composition order: CV cleanup (if any) still runs as part of the innermost
     * delegate's own {@code publish}, and the live-update wrapper sees every event exactly once,
     * regardless of whether CV cleanup is also wired.
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
     * default) wires the real {@code LiveUpdateRegistry} (vision-api, component-scanned — same
     * bean that backs {@code GET /api/live}, obtained via {@link ObjectProvider} for the same
     * reason {@link PersistenceWiringConfiguration}'s six repository-port beans do: the registry's
     * own {@code @ConditionalOnProperty} means it may not exist as a bean at all on the {@code
     * false} branch, and {@code getObject()} is only ever called on the branch where {@link
     * VisionLiveProperties#enabled()} guarantees it does); {@code false} wires {@link
     * NoopLiveUpdatePublisher}, and {@code /api/live} 404s (its controller is gated by the exact
     * same property key, independently, directly via {@code @ConditionalOnProperty}).
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
     * Synthetic 1&nbsp;Hz {@link TelemetrySourcePort}, collected (alongside
     * any other registered {@code TelemetrySourcePort} beans) into {@link
     * #usageTracker}'s {@code List<TelemetrySourcePort>} so a {@code sim}
     * telemetry-capable device produces a demoable usage trail with zero
     * hardware, per docs/ASSET-MODEL-PLAN.md §0.7.
     */
    @Bean
    public SimulatedTelemetrySource simulatedTelemetrySource() {
        return new SimulatedTelemetrySource();
    }

    /**
     * RX half of the MAVLink TX/RX pair (docs/MVP2-PLAN.md X-a): ingests MAVLink 2 telemetry over
     * UDP — from a real telemetry radio, ArduPilot/PX4 SITL, or {@link #mavlinkFeedTransmitter} —
     * for {@code "mavlink"}-protocol telemetry devices. Collected (alongside {@link
     * #simulatedTelemetrySource} and any other registered {@code TelemetrySourcePort} beans) into
     * {@link #usageTracker}'s {@code List<TelemetrySourcePort>}.
     */
    @Bean
    public MavlinkTelemetrySource mavlinkTelemetrySource() {
        return new MavlinkTelemetrySource();
    }

    /**
     * The one deliberate command-TX path (docs/DRONE-INFRA-PLAN.md I-e, Stage 1 — "bring it
     * home"): sends {@code MAV_CMD_DO_SET_MODE} return-to-home reusing the exact same shared
     * socket {@link #mavlinkTelemetrySource} already has open for RX, rather than opening a second
     * one of its own — the same instance-borrowing pattern {@code
     * DiscoveryWiringConfiguration#mavlinkHeartbeatScanner} already uses. {@link
     * #mavlinkTelemetrySource} is wired unconditionally (see its own javadoc), so this bean is too
     * — there is no {@code vision.mavlink.*}-shaped property to key a conditional off of. Declared
     * as the concrete adapter type, like {@link #mavlinkFeedTransmitter}, not the {@link
     * FlightCommandPort} interface — {@link #flightCommandService} below resolves it by that
     * interface type regardless, the same "concrete bean, matched by interface where needed" idiom
     * this class already uses throughout.
     */
    @Bean
    public MavlinkFlightCommander mavlinkFlightCommander(MavlinkTelemetrySource mavlinkTelemetrySource) {
        return new MavlinkFlightCommander(mavlinkTelemetrySource);
    }

    /**
     * Selects the {@link StreamPublisherPort} implementation per {@link
     * VisionPublishProperties#enabled()}: the mediamtx-backed publisher
     * (default) pushes RTSP to the mediamtx sidecar configured by {@link
     * VisionPublishProperties.Mediamtx#rtspBase()}, so browsers can watch
     * published streams over HLS; disabling it falls back to the no-op
     * publisher (e.g. local development/testing without mediamtx running).
     *
     * <p>The publisher's {@code hlsViewBase} is {@link
     * VisionPublishProperties#viewBase()} (app-relative {@code /hls} by
     * default) rather than {@link VisionPublishProperties.Mediamtx#hlsBase()}
     * — the latter is now purely the internal upstream {@link
     * #hlsProxyUpstreamBase} forwards to, never handed to viewers. See
     * {@link VisionPublishProperties}'s javadoc for the full rationale.
     *
     * <p>{@code whepViewBase} is {@link VisionPublishProperties.Mediamtx#whepBase()} directly
     * (docs/MVP2-PLAN.md §L) — unlike {@code hlsViewBase}, WHEP has no app-relative proxy
     * counterpart, so this must already be an address the viewer's browser can reach; see {@link
     * VisionPublishProperties}'s "WHEP has no third base" javadoc section.
     */
    @Bean
    public StreamPublisherPort streamPublisherPort(VisionPublishProperties properties) {
        if (properties.enabled()) {
            VisionPublishProperties.Mediamtx mediamtx = properties.mediamtx();
            return new MediamtxStreamPublisher(mediamtx.rtspBase(), properties.viewBase(), mediamtx.whepBase());
        }
        return new NoopStreamPublisher();
    }

    /**
     * The upstream {@link HlsProxyController} (component-scanned from {@code
     * vision-api}, like every other REST controller) forwards {@code
     * /hls/**} requests to — mediamtx's actual HLS egress address, e.g.
     * {@code http://localhost:18888}. Supplied as a bean (rather than
     * {@code HlsProxyController} itself being constructed here) so the
     * controller stays a plain component-scanned bean exactly like {@code
     * AssetController}/{@code DeviceController}/etc., with only its {@link
     * URI} collaborator wired from this module's configuration.
     */
    @Bean
    public URI hlsProxyUpstreamBase(VisionPublishProperties properties) {
        return properties.mediamtx().hlsBase();
    }

    /**
     * The detection-model roster {@code CvModelsController} (component-scanned from {@code
     * vision-api}) serves at {@code GET /api/cv/models} (docs/CV-CONTROL-PLAN.md §4's frozen wire
     * contract) — the Fly cockpit's model picker builds its dropdown from exactly this list.
     *
     * <p>Supplied as a plain {@code List<CvModelResponse>} bean (rather than {@code
     * CvModelsController} itself being constructed here), the same "raw collaborator, not a domain
     * port" pattern {@link #hlsProxyUpstreamBase} already uses — the controller stays a plain
     * component-scanned bean like every other REST controller, with only its collaborator wired
     * from this module's configuration.
     *
     * <p><strong>Deliberately a static, in-source constant, not the dormant {@code
     * ModelRegistryPort}</strong> (docs/CV-CONTROL-PLAN.md §D): that port models versioned
     * promote/rollback (a Phase-3 training-studio concern) and has no implementation — wiring it now
     * for a picker that only needs a display list would be over-building. This roster changes at
     * deploy time (edit this method, rebuild), not at runtime; a future real source, if one is ever
     * needed, is either that port or a small {@code cv-service} roster RPC (its own {@code
     * ModelRegistry} already knows the local checkpoint set) — a documented seam, not built now.
     *
     * <p>{@code yolo26n.pt} is listed first and is the default ({@link
     * com.drones.vision.domain.model.PipelineConfig#defaults()}) — the fast, closed-set,
     * people+vehicles model. {@code yoloe-26s-seg-pf.pt} is the opt-in open-vocabulary model
     * (materially slower on CPU); its {@code defaultLabelFilter} is deliberately <strong>empty</strong>
     * rather than a curated preset — Wave A's real measurement found the model's 4585-class vocabulary
     * spells "building" a dozen different image-dependent ways ({@code building}/{@code
     * skyscraper}/{@code downtown}/{@code Prague Castle}/...), so a fixed exact-match preset would
     * silently drop most buildings; the UI seeds its class chips from labels actually observed in the
     * live detection stream instead (docs/CV-CONTROL-PLAN.md §E).
     */
    @Bean
    public List<CvModelResponse> cvModelRoster() {
        return List.of(
                new CvModelResponse("yolo26n.pt", "General (people & vehicles, fast)", "general", false, List.of()),
                new CvModelResponse("orion12l.pt", "Military vehicles", "specialized", false, List.of()),
                new CvModelResponse("yoloe-26s-seg-pf.pt", "Everything (incl. buildings, slower)", "open-vocab",
                        true, List.of()));
    }

    /**
     * Selects the {@link DetectionPort} implementation per {@link VisionCvProperties#enabled()}
     * (docs/MVP1-PLAN.md §C7 bullet 4): {@code true} wires {@code GrpcDetectionPort}
     * (adapter-cv-grpc) against {@link VisionCvProperties#host()}/{@link
     * VisionCvProperties#port()}, with its wire-tuning knobs from {@link
     * VisionCvProperties#detectWidth()}/{@link VisionCvProperties#jpegQuality()}
     * (docs/REMOTE-CV-PLAN.md P1 item 5 — e.g. a narrower {@code detectWidth} over a slow VPN
     * link needs no rebuild); {@code false} (the default) keeps today's {@link
     * NoopDetectionPort}. No explicit {@code destroyMethod} is declared here — {@code @Bean}'s
     * default {@code "(inferred)"} destroy method already detects and calls a public no-arg
     * {@code close()}/{@code shutdown()} on whichever concrete type the bean actually is at
     * shutdown, so {@code GrpcDetectionPort#close()} (which shuts its gRPC channel down) still
     * runs on context close without needing an explicit name — unlike an <em>explicit</em> {@code
     * destroyMethod = "close"}, which this Spring version validates eagerly at bean-creation time
     * and fails hard with {@code BeanDefinitionValidationException} for the branch where the bean
     * is a {@link NoopDetectionPort} (no such method at all).
     */
    @Bean
    public DetectionPort detectionPort(VisionCvProperties cvProperties) {
        if (cvProperties.enabled()) {
            return new GrpcDetectionPort(cvProperties.host(), cvProperties.port(),
                    cvProperties.detectWidth(), cvProperties.jpegQuality());
        }
        return new NoopDetectionPort();
    }

    @Bean
    public DeviceService deviceService(DeviceRepositoryPort deviceRepositoryPort,
                                        StreamService streamService,
                                        AuditTrailPort auditTrailPort,
                                        EventPublisherPort eventPublisherPort) {
        return new DefaultDeviceService(deviceRepositoryPort, streamService, auditTrailPort, eventPublisherPort);
    }

    /**
     * Append-only record of who changed the fleet. In-memory, unconditionally — {@code
     * AuditTrailPort} was out of scope for both docs/MVP2-PLAN.md P-a and P-b (see this class's
     * javadoc); see {@link InMemoryAuditTrail}.
     *
     * <p>When {@link VisionLiveProperties#enabled()} is {@code true} (docs/REALTIME-PLAN.md §4),
     * wrapped in {@link LiveUpdateAuditTrail}, which announces a "fleet changed" live update for
     * every recorded entry — every asset/device create/update/state-change/assign/unassign audits
     * exactly one entry regardless of whether it also raises a domain event, making this the
     * uniform seam for that half of "assets/devices/streams lifecycle" (see that class's own
     * javadoc for why {@code STREAM_STARTED}/{@code STREAM_STOPPED} instead flow through {@link
     * #eventPublisherPort}).
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
     * Debounced detection events (docs/MVP2-PLAN.md §E, E-a). In-memory, unconditionally — like
     * {@link #auditTrailPort}, persistence was explicitly out of scope for this feature (deferred
     * to a future persistence cycle); see {@link InMemoryDetectionEventRepository}.
     *
     * <p>When {@link VisionLiveProperties#enabled()} is {@code true} (docs/REALTIME-PLAN.md §4,
     * extended for the {@code detection-events} live topic), wrapped in {@link
     * LiveUpdateDetectionEventRepository}, which announces every {@code save} (open/advance/close)
     * as a live update — the same "decorate the port every write already goes through" seam {@link
     * #auditTrailPort} uses, applied here since {@code DetectionEventEngine} (vision-application)
     * already calls this port at exactly the moments a live viewer cares about.
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
     * {@link #usageTracker} below (its 7-argument constructor) as a nullable-by-convention but
     * always-real collaborator here, the same "no `if enabled`" posture as {@code
     * liveUpdatePublisherPort} threaded through the same constructor.
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
     * Drives {@link com.drones.vision.domain.model.AssetUsage} lifecycle and
     * telemetry sampling from {@link StreamService}'s start/stop
     * notifications — see {@link #streamService} below, which is
     * constructed with this collaborator via {@code DefaultStreamService}'s
     * 7-argument constructor.
     *
     * <p>{@code liveUpdatePublisherPort} (docs/REALTIME-PLAN.md §4) is threaded through
     * unconditionally — it is always a real bean (either the SSE registry or {@link
     * com.drones.vision.app.devsupport.NoopLiveUpdatePublisher}, see {@link
     * #liveUpdatePublisherPort}), so every appended telemetry sample is announced regardless of
     * {@link VisionLiveProperties#enabled()}; the no-op branch makes that announcement free.
     *
     * <p>{@code geofenceMonitor} (docs/OPS-CORE-PLAN.md §G) is likewise threaded through
     * unconditionally — {@link #geofenceMonitor} is always a real bean, so every appended
     * telemetry sample is also evaluated for geofence breaches regardless of whether any zone has
     * been configured yet (an empty zone set is simply never a breach).
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
     * {@code liveUpdatePublisherPort} (docs/REALTIME-PLAN.md §4) is threaded through
     * unconditionally, same reasoning as {@link #usageTracker} above — every stream pipeline this
     * service starts announces its completed detection results (attributed to the owning asset,
     * resolved once at start via {@code usageTracker}) regardless of {@link
     * VisionLiveProperties#enabled()}.
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
                                        LiveUpdatePublisherPort liveUpdatePublisherPort) {
        return new DefaultStreamService(deviceRepositoryPort, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisherPort, usageTracker, overlayPort,
                detectionEventRepositoryPort, liveUpdatePublisherPort);
    }

    /**
     * Assets — creation, editing, lifecycle, and asset-level streaming. Devices are reached
     * through {@link DeviceService} rather than the repository, so rules that belong to a source
     * (stop its stream, write an audit line) live in exactly one place.
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
     * service behind {@code FlightCommandController} (vision-api, component-scanned). {@link
     * #mavlinkFlightCommander} is the one {@link FlightCommandPort} bean in this context today,
     * resolved here by its interface type — a one-line assembly over already-wired collaborators,
     * mirroring {@link #categoryService}'s shape.
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
     * (vision-api, component-scanned) — the one bean R-a's own writeup flagged as missing, closed
     * here. All four collaborators are already wired above/in {@link
     * PersistenceWiringConfiguration}, so this is a one-line assembly, mirroring {@link
     * #categoryService}'s shape. {@code streamPublisherPort} (up from three collaborators to four,
     * R-b) is the same bean {@link #streamService} already resolves {@code viewUrl}/{@code
     * whepUrl} through — {@link DefaultReplayService#recordingFor} calls its {@code playbackUrl}
     * for the resolved usage window.
     */
    @Bean
    public ReplayService replayService(AssetUsageRepositoryPort assetUsageRepositoryPort,
                                        TelemetryRepositoryPort telemetryRepositoryPort,
                                        DetectionRepositoryPort detectionRepositoryPort,
                                        StreamPublisherPort streamPublisherPort) {
        return new DefaultReplayService(assetUsageRepositoryPort, telemetryRepositoryPort, detectionRepositoryPort,
                streamPublisherPort);
    }

    /**
     * The manager dashboard's single aggregated read (docs/MVP3-PLAN.md C-a): the read side behind
     * {@code FleetController} (vision-api, component-scanned) — a one-line assembly over four
     * already-wired collaborators, mirroring {@link #replayService}'s shape.
     */
    @Bean
    public FleetSummaryService fleetSummaryService(AssetService assetService, StreamService streamService,
                                                     UsageTracker usageTracker,
                                                     DetectionEventRepositoryPort detectionEventRepositoryPort) {
        return new DefaultFleetSummaryService(assetService, streamService, usageTracker,
                detectionEventRepositoryPort);
    }

    /**
     * One asset's flight-utilization stats (docs/ASSET-MANAGER-PAGE-PLAN.md, Wave A): the read
     * side behind {@code AssetStatsController}'s {@code GET /api/assets/{id}/stats} (vision-api,
     * component-scanned) — a one-line assembly over two already-wired collaborators, mirroring
     * {@link #replayService}'s shape.
     */
    @Bean
    public AssetStatsService assetStatsService(AssetUsageRepositoryPort assetUsageRepositoryPort,
                                                UsageTracker usageTracker) {
        return new DefaultAssetStatsService(assetUsageRepositoryPort, usageTracker);
    }

    /**
     * Test-before-save connection probe (docs/UX-REWORK-PLAN.md §U-d item 3, UX-DESIGN.md §5.1):
     * the read side behind {@code DeviceProbeController} (vision-api, component-scanned). Reuses
     * {@link #videoSourceRegistry} (the exact same adapter-selection {@link StreamService} itself
     * streams through) plus the {@code List<TelemetrySourcePort>} beans Spring already collects
     * for {@link #usageTracker} — no new collaborator type, a one-line assembly mirroring {@link
     * #replayService}'s shape.
     */
    @Bean
    public ProbeService probeService(VideoSourceRegistry videoSourceRegistry,
                                      List<TelemetrySourcePort> telemetrySources) {
        return new DefaultProbeService(videoSourceRegistry, telemetrySources);
    }

    /**
     * TX (transmit) half of docs/CYCLES-PLAN.md §3's RX/TX doctrine: pushes a
     * {@code transport=rtsp} simulation's video file to the same mediamtx sidecar {@link
     * #streamPublisherPort} pushes viewer egress to — {@link VisionPublishProperties.Mediamtx#rtspBase()}
     * is reused as-is rather than adding a new property, since it is already exactly "the mediamtx
     * RTSP push target this app knows about" and both users push to the same mediamtx instance.
     */
    @Bean
    public RtspFeedTransmitter rtspFeedTransmitter(VisionPublishProperties properties) {
        return new RtspFeedTransmitter(properties.mediamtx().rtspBase());
    }

    /**
     * TX half of the mjpeg TX/RX pair (docs/CYCLES-PLAN.md §5): serves a {@code transport=mjpeg}
     * simulation's video file as an HTTP {@code multipart/x-mixed-replace} stream on its own
     * ephemeral {@code 127.0.0.1} port — no constructor config needed, unlike {@link
     * #rtspFeedTransmitter} (which needs a target base), since this transmitter serves its own
     * server rather than pushing to an external one (see adapter-mjpeg/MODULE.md). {@code
     * destroyMethod = "close"} so Spring shuts its shared {@code HttpServer}/dispatch pool down
     * (releasing the ephemeral port) on context close — {@code stop(FeedId)} alone never does
     * that, only {@link MjpegFeedTransmitter#close()} does.
     */
    @Bean(destroyMethod = "close")
    public MjpegFeedTransmitter mjpegFeedTransmitter() {
        return new MjpegFeedTransmitter();
    }

    /**
     * TX half of the MAVLink TX/RX pair (docs/MVP2-PLAN.md X-a): emits a synthetic MAVLink 2
     * telemetry stream (HEARTBEAT/SYS_STATUS/GLOBAL_POSITION_INT) driven by a flight route, for
     * zero-hardware rehearsal of {@link #mavlinkTelemetrySource} (or any real MAVLink ground
     * station). No constructor config, like {@link #mjpegFeedTransmitter} — unlike {@code rtsp}/
     * {@code mjpeg}, the destination is per-feed ({@link com.drones.vision.domain.model.FeedSpec#source()}
     * is itself the {@code udp://host:port} to push to), not a shared base; see
     * adapter-mavlink/MODULE.md for the full reasoning. Not yet reachable via {@link
     * #simulationService}/{@code SimulationTransport} — that dispatch integration is a follow-up,
     * out of X-a's scope; this bean is independently usable via the plain device/asset APIs today.
     */
    @Bean
    public MavlinkFeedTransmitter mavlinkFeedTransmitter() {
        return new MavlinkFeedTransmitter();
    }

    /**
     * Selects the {@link FeedTransmitterPort} adapter for a simulation's {@code transport}
     * (docs/CYCLES-PLAN.md §5) — the TX-side mirror of {@link #videoSourceRegistry}, generalizing
     * {@link #simulationService}'s single {@code FeedTransmitterPort} dependency (docs/CYCLES-PLAN.md
     * §3) now that a second transmit protocol ({@code mjpeg}) exists alongside {@code rtsp}.
     */
    @Bean
    public FeedTransmitterRegistry feedTransmitterRegistry(List<FeedTransmitterPort> feedTransmitters) {
        return new FeedTransmitterRegistry(feedTransmitters);
    }

    /**
     * The one-call, zero-hardware simulation entry point (docs/CYCLES-PLAN.md §1b, §3, §5): turns
     * a video file path into a registered {@code simulated}-category asset via {@link
     * #assetService}, reusing every rule it already enforces rather than duplicating asset
     * creation here. {@link #feedTransmitterRegistry} backs {@code transport=rtsp}/{@code mjpeg}
     * simulations, selecting {@link #rtspFeedTransmitter}/{@link #mjpegFeedTransmitter} by protocol.
     *
     * <p>{@code properties.mediamtx().rtspBase()} is reused as-is (no new property — same
     * "already exactly the mediamtx RTSP push target this app knows about" reasoning as {@link
     * #rtspFeedTransmitter}) so {@link DefaultSimulationService#resumeAll()} (simulated-feed
     * resume-on-boot) can recognize which persisted {@code rtsp} devices are this app's own TX-fed
     * feeds — see {@link SimulationApplicationRunner} for what actually calls it.
     */
    @Bean
    public SimulationService simulationService(AssetService assetService,
                                                CategoryRepositoryPort categoryRepositoryPort,
                                                FeedTransmitterRegistry feedTransmitterRegistry,
                                                VisionPublishProperties properties) {
        return new DefaultSimulationService(assetService, categoryRepositoryPort, feedTransmitterRegistry,
                properties.mediamtx().rtspBase());
    }

    /**
     * Simulated-feed resume-on-boot (per the design sketch in vision-application/MODULE.md):
     * restarts the TX feed for every persisted simulated asset whose {@code rtsp} video device is
     * one of this app's own TX-fed feeds — see {@link SimulationResumeRunner}/{@code
     * DefaultSimulationService#resumeAll()} for the mechanism itself. {@code enabled} is resolved
     * once, here, from both gates: {@link VisionPersistenceProperties#enabled()} (the in-memory
     * profile has nothing to resume after a restart either — its assets are gone too) <em>and</em>
     * {@link VisionSimulationProperties#resumeOnBoot()} (an explicit opt-out, default {@code
     * true}). Always registered as a bean (unlike {@code LiveController}'s conditionally-absent
     * pattern) — a two-property AND condition has no single {@code @ConditionalOnProperty} to
     * express it cleanly, and an always-present bean that resolves to a no-op when either gate is
     * off is simpler and just as testable (see {@code PersistenceWiringConfigurationTest}'s own
     * precedent for testing a property-gated {@code @Bean} method by calling it directly, without
     * a live Spring context).
     */
    @Bean
    public ApplicationRunner simulationResumeRunner(SimulationService simulationService,
                                                     VisionPersistenceProperties persistenceProperties,
                                                     VisionSimulationProperties simulationProperties) {
        return new SimulationResumeRunner(simulationService,
                persistenceProperties.enabled() && simulationProperties.resumeOnBoot());
    }
}

package com.drones.vision.app;

import com.drones.vision.adapter.cvgrpc.GrpcDetectionPort;
import com.drones.vision.adapter.mjpeg.MjpegFeedTransmitter;
import com.drones.vision.adapter.mjpeg.MjpegVideoSource;
import com.drones.vision.adapter.overlay.Java2DOverlayRenderer;
import com.drones.vision.adapter.publishhls.MediamtxStreamPublisher;
import com.drones.vision.adapter.rtsp.FfmpegVideoSource;
import com.drones.vision.adapter.rtsp.RtspFeedTransmitter;
import com.drones.vision.adapter.simulation.SimulatedTelemetrySource;
import com.drones.vision.adapter.simulation.SimulatedVideoSource;
import com.drones.vision.api.HlsProxyController;
import com.drones.vision.app.devsupport.DevPrincipal;
import com.drones.vision.app.devsupport.InMemoryAssetRepository;
import com.drones.vision.app.devsupport.InMemoryAssetUsageRepository;
import com.drones.vision.app.devsupport.InMemoryAuditTrail;
import com.drones.vision.app.devsupport.InMemoryCategoryRepository;
import com.drones.vision.app.devsupport.InMemoryDetectionRepository;
import com.drones.vision.app.devsupport.InMemoryDeviceRepository;
import com.drones.vision.app.devsupport.InMemoryTelemetryRepository;
import com.drones.vision.app.devsupport.LoggingEventPublisher;
import com.drones.vision.app.devsupport.NoopDetectionPort;
import com.drones.vision.app.devsupport.NoopStreamPublisher;
import com.drones.vision.application.AssetService;
import com.drones.vision.application.DefaultAssetService;
import com.drones.vision.application.DefaultCategoryService;
import com.drones.vision.application.DefaultDeviceService;
import com.drones.vision.application.DefaultSimulationService;
import com.drones.vision.application.DefaultStreamService;
import com.drones.vision.application.CategoryService;
import com.drones.vision.application.DeviceService;
import com.drones.vision.application.FeedTransmitterRegistry;
import com.drones.vision.application.SimulationService;
import com.drones.vision.application.StreamService;
import com.drones.vision.application.UsageTracker;
import com.drones.vision.application.VideoSourceRegistry;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.port.out.AssetRepositoryPort;
import com.drones.vision.domain.port.out.AssetUsageRepositoryPort;
import com.drones.vision.domain.port.out.AuditTrailPort;
import com.drones.vision.domain.port.out.CategoryRepositoryPort;
import com.drones.vision.domain.port.out.DetectionPort;
import com.drones.vision.domain.port.out.DetectionRepositoryPort;
import com.drones.vision.domain.port.out.DeviceRepositoryPort;
import com.drones.vision.domain.port.out.EventPublisherPort;
import com.drones.vision.domain.port.out.FeedTransmitterPort;
import com.drones.vision.domain.port.out.OverlayPort;
import com.drones.vision.domain.port.out.StreamPublisherPort;
import com.drones.vision.domain.port.out.TelemetryRepositoryPort;
import com.drones.vision.domain.port.out.TelemetrySourcePort;
import com.drones.vision.domain.port.out.VideoSourcePort;
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
 * <p>Ports that don't yet have a real adapter (persistence, event bus) are
 * wired to in-process dev-support fallbacks so the platform runs end to end
 * from Phase 0 onward; each fallback's javadoc names the adapter that will
 * replace it and in which phase. Stream egress ({@link StreamPublisherPort})
 * is real as of Phase 1: {@link #streamPublisherPort(VisionPublishProperties)}
 * selects between the mediamtx-backed publisher and the no-op fallback based
 * on {@link VisionPublishProperties}. CV inference ({@link DetectionPort})
 * is real as of docs/MVP1-PLAN.md §C7: {@link #detectionPort(VisionCvProperties)}
 * selects between {@code GrpcDetectionPort} (adapter-cv-grpc) and the no-op
 * fallback based on {@link VisionCvProperties}; see {@link
 * #eventPublisherPort(DetectionPort, VisionCvProperties)} for how the gRPC
 * session's per-stream lifecycle is cleaned up.
 */
@Configuration
@EnableConfigurationProperties({VisionPublishProperties.class, VisionCvProperties.class})
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

    @Bean
    public VideoSourceRegistry videoSourceRegistry(List<VideoSourcePort> videoSources) {
        return new VideoSourceRegistry(videoSources);
    }

    @Bean
    public DeviceRepositoryPort deviceRepositoryPort() {
        return new InMemoryDeviceRepository();
    }

    @Bean
    public DetectionRepositoryPort detectionRepositoryPort() {
        return new InMemoryDetectionRepository();
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
     */
    @Bean
    public EventPublisherPort eventPublisherPort(DetectionPort detectionPort, VisionCvProperties cvProperties) {
        EventPublisherPort delegate = new LoggingEventPublisher();
        if (cvProperties.enabled() && detectionPort instanceof GrpcDetectionPort grpcDetectionPort) {
            return new DetectionSessionCleanupEventPublisher(delegate, grpcDetectionPort);
        }
        return delegate;
    }

    @Bean
    public CategoryRepositoryPort categoryRepositoryPort() {
        return new InMemoryCategoryRepository();
    }

    @Bean
    public AssetRepositoryPort assetRepositoryPort() {
        return new InMemoryAssetRepository();
    }

    @Bean
    public AssetUsageRepositoryPort assetUsageRepositoryPort() {
        return new InMemoryAssetUsageRepository();
    }

    @Bean
    public TelemetryRepositoryPort telemetryRepositoryPort() {
        return new InMemoryTelemetryRepository();
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
     */
    @Bean
    public StreamPublisherPort streamPublisherPort(VisionPublishProperties properties) {
        if (properties.enabled()) {
            VisionPublishProperties.Mediamtx mediamtx = properties.mediamtx();
            return new MediamtxStreamPublisher(mediamtx.rtspBase(), properties.viewBase());
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
     * Selects the {@link DetectionPort} implementation per {@link VisionCvProperties#enabled()}
     * (docs/MVP1-PLAN.md §C7 bullet 4): {@code true} wires {@code GrpcDetectionPort}
     * (adapter-cv-grpc) against {@link VisionCvProperties#host()}/{@link
     * VisionCvProperties#port()}; {@code false} (the default) keeps today's {@link
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
            return new GrpcDetectionPort(cvProperties.host(), cvProperties.port());
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
     * The principal every control-plane change is attributed to until authentication lands
     * (ARCHITECTURE.md §6). Supplied as a bean so {@code vision-api}'s {@code CurrentUser} has
     * exactly one thing to replace when it starts reading the identity from a JWT — the acting
     * user is never a constructor dependency of a service.
     */
    @Bean
    public Ownership actingOwnership() {
        return DevPrincipal.OWNERSHIP;
    }

    /**
     * Append-only record of who changed the fleet. In-memory until
     * {@code adapter-persistence} lands — see {@link InMemoryAuditTrail}.
     */
    @Bean
    public AuditTrailPort auditTrailPort() {
        return new InMemoryAuditTrail();
    }

    /**
     * Drives {@link com.drones.vision.domain.model.AssetUsage} lifecycle and
     * telemetry sampling from {@link StreamService}'s start/stop
     * notifications — see {@link #streamService} below, which is
     * constructed with this collaborator via {@code DefaultStreamService}'s
     * 7-argument constructor.
     */
    @Bean
    public UsageTracker usageTracker(AssetRepositoryPort assetRepositoryPort,
                                      DeviceRepositoryPort deviceRepositoryPort,
                                      AssetUsageRepositoryPort assetUsageRepositoryPort,
                                      TelemetryRepositoryPort telemetryRepositoryPort,
                                      List<TelemetrySourcePort> telemetrySources) {
        return new UsageTracker(assetRepositoryPort, deviceRepositoryPort, assetUsageRepositoryPort,
                telemetryRepositoryPort, telemetrySources);
    }

    @Bean
    public StreamService streamService(DeviceRepositoryPort deviceRepositoryPort,
                                        VideoSourceRegistry videoSourceRegistry,
                                        DetectionPort detectionPort,
                                        StreamPublisherPort streamPublisherPort,
                                        DetectionRepositoryPort detectionRepositoryPort,
                                        EventPublisherPort eventPublisherPort,
                                        UsageTracker usageTracker,
                                        OverlayPort overlayPort) {
        return new DefaultStreamService(deviceRepositoryPort, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisherPort, usageTracker, overlayPort);
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
     */
    @Bean
    public SimulationService simulationService(AssetService assetService,
                                                CategoryRepositoryPort categoryRepositoryPort,
                                                FeedTransmitterRegistry feedTransmitterRegistry) {
        return new DefaultSimulationService(assetService, categoryRepositoryPort, feedTransmitterRegistry);
    }
}

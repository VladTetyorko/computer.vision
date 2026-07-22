package com.drones.vision.app;

import com.drones.vision.adapter.publishhls.MediamtxStreamPublisher;
import com.drones.vision.adapter.rtsp.FfmpegVideoSource;
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
 * adapter-publish-hls}, and the dev-support fallbacks in {@link
 * com.drones.vision.app.devsupport}) — enforced by {@code ArchitectureTest}.
 * {@code vision-domain} and {@code vision-application} themselves stay free
 * of Spring annotations; all {@code @Bean}/{@code @Configuration} wiring
 * lives here.
 *
 * <p>Ports that don't yet have a real adapter (persistence, event bus,
 * CV inference) are wired to in-process dev-support fallbacks so the
 * platform runs end to end from Phase 0 onward; each fallback's javadoc
 * names the adapter that will replace it and in which phase. Stream egress
 * ({@link StreamPublisherPort}) is real as of Phase 1: {@link
 * #streamPublisherPort(VisionPublishProperties)} selects between the
 * mediamtx-backed publisher and the no-op fallback based on {@link
 * VisionPublishProperties}.
 */
@Configuration
@EnableConfigurationProperties(VisionPublishProperties.class)
public class WiringConfiguration {

    @Bean
    public SimulatedVideoSource simulatedVideoSource() {
        return new SimulatedVideoSource();
    }

    @Bean
    public FfmpegVideoSource ffmpegVideoSource() {
        return new FfmpegVideoSource();
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

    @Bean
    public EventPublisherPort eventPublisherPort() {
        return new LoggingEventPublisher();
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

    @Bean
    public DetectionPort detectionPort() {
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
                                        UsageTracker usageTracker) {
        return new DefaultStreamService(deviceRepositoryPort, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisherPort, usageTracker);
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
     * The one-call, zero-hardware simulation entry point (docs/CYCLES-PLAN.md §1b): turns a video
     * file path into a registered {@code simulated}-category asset via {@link #assetService},
     * reusing every rule it already enforces rather than duplicating asset creation here.
     */
    @Bean
    public SimulationService simulationService(AssetService assetService,
                                                CategoryRepositoryPort categoryRepositoryPort) {
        return new DefaultSimulationService(assetService, categoryRepositoryPort);
    }
}

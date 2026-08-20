package com.drones.vision.app.config.wiring;

import com.drones.vision.adapter.cvgrpc.CvChannelSupervisor;
import com.drones.vision.adapter.cvgrpc.GrpcPulledGeolocationPort;
import com.drones.vision.adapter.cvgrpc.GrpcReferenceIndexPort;
import com.drones.vision.adapter.cvgrpc.NoopGeolocationPort;
import com.drones.vision.api.support.VisualGeoProperties;
import com.drones.vision.adapter.tiles.HttpTileSource;
import com.drones.vision.adapter.tiles.WaybackReleaseCatalog;
import com.drones.vision.adapter.tiles.WaybackTileSource;
import com.drones.vision.app.config.properties.VisionGeoVisualProperties;
import com.drones.vision.app.devsupport.NoopReferenceIndexPort;
import com.drones.vision.app.geo.VisualGeoRunner;
import com.drones.vision.flight.application.DefaultTrackCorrectionService;
import com.drones.vision.flight.application.TrackCorrectionService;
import com.drones.vision.flight.domain.port.TrackCorrectionLiveUpdatePort;
import com.drones.vision.flight.domain.port.TrackCorrectionRepositoryPort;
import com.drones.vision.perception.application.geo.DefaultGeolocationSessionService;
import com.drones.vision.perception.application.geo.DefaultReferenceRegionService;
import com.drones.vision.perception.application.geo.GeolocationSessionService;
import com.drones.vision.perception.application.geo.ReferenceRegionService;
import com.drones.vision.perception.application.pipeline.UsageTracker;
import com.drones.vision.perception.application.stream.StreamService;
import com.drones.vision.perception.domain.port.PulledGeolocationPort;
import com.drones.vision.perception.domain.port.ReferenceIndexPort;
import com.drones.vision.perception.domain.port.ReferenceTileSourcePort;
import com.drones.vision.platform.EventPublisherPort;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort;
import io.grpc.ManagedChannel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires visual geolocation (docs/plans/active/VISUAL-GEO-V2-PLAN.md, H5) — a <b>new file</b>, not an
 * edit to a shared one (the plan's own H5 deliverable line is explicit about this), mirroring {@code
 * FixedCameraGeoWiringConfiguration}'s shape: unconditional beans for the driven ports and
 * application services, so {@code GeoRegionController}/{@code GeoCorrectionController}
 * (component-scanned from {@code vision-api}, unconditional constructor dependencies exactly like
 * {@code CameraPoseController}) always resolve — and exactly one flag-gated bean, {@link
 * #visualGeoRunner}, which is the only thing D9 actually requires to not exist when {@code
 * vision.geo.visual.enabled=false}.
 *
 * <h2>Real vs. no-op port selection</h2>
 * {@link #pulledGeolocationPort} and {@link #referenceIndexPort} select between a real gRPC-backed
 * implementation (reusing {@link CvWiring}'s shared {@link ManagedChannel}/{@link
 * CvChannelSupervisor} — D3, "one channel to cv-service, not two") and a no-op fallback, based on
 * {@link VisionGeoVisualProperties#enabled()}. This is the one condition this class evaluates itself
 * rather than delegating to {@code @ConditionalOnProperty}: the controllers above need <em>some</em>
 * {@link ReferenceRegionService}/{@link com.drones.vision.perception.application.geo.GeolocationSessionService}
 * bean unconditionally, which in turn need <em>some</em> port underneath — {@link NoopGeolocationPort}
 * (adapter-cv-grpc, already written anticipating exactly this selection) and {@link
 * NoopReferenceIndexPort} (vision-app devsupport, written this wave since no such class existed) fill
 * that role when the flag is off.
 *
 * <p>{@link #referenceTileSourcePort} needs no no-op counterpart: {@code
 * GeoRegionController.ingest()} always calls {@code VisualGeoProperties#requireEnabled()} first
 * (vision-api, before ever reaching {@link ReferenceRegionService}), so {@link HttpTileSource}/{@link
 * WaybackTileSource} can be wired for real, unconditionally, at zero risk — they are simply never
 * invoked while the flag is off.
 */
@Configuration
@EnableConfigurationProperties(VisionGeoVisualProperties.class)
public class VisualGeoWiringConfiguration {

    /**
     * The {@code vision-api}-side bridge {@link VisualGeoProperties}'s own javadoc documents in
     * full: {@code vision-api} may not depend on this module's {@code @ConfigurationProperties}
     * type, so its plain framework-free mirror is populated here instead — the same bridge pattern
     * {@code FixedCameraGeoWiringConfiguration#fixedCameraGeoApiProperties}/{@code
     * OnboardingWiringConfiguration#onboardingApiProperties} already establish. {@code
     * GeoRegionController}/{@code GeoCorrectionController} call {@link VisualGeoProperties#requireEnabled()}
     * first, before ever reaching any collaborator below — the D9 gate.
     */
    @Bean
    public VisualGeoProperties visualGeoApiProperties(VisionGeoVisualProperties properties) {
        return new VisualGeoProperties(properties.enabled());
    }

    /**
     * Selects {@link HttpTileSource} or {@link WaybackTileSource} per {@link
     * VisionGeoVisualProperties.Tiles#waybackMultiDate()} (O6, default {@code false} — the
     * appearance-domain gap, not seasonal occlusion, is today's bottleneck). Wired unconditionally —
     * see class javadoc for why that carries no risk while the feature is disabled.
     */
    @Bean
    public ReferenceTileSourcePort referenceTileSourcePort(VisionGeoVisualProperties properties) {
        if (properties.tiles().waybackMultiDate()) {
            return new WaybackTileSource(WaybackReleaseCatalog.defaults(), properties.toTileSourceSettings());
        }
        return new HttpTileSource(properties.toTileSourceSettings());
    }

    /**
     * Real {@link GrpcPulledGeolocationPort} when enabled (sharing {@link CvWiring#cvGrpcChannel}/
     * {@link CvWiring#cvChannelSupervisor}, D3), {@link NoopGeolocationPort} otherwise — see class
     * javadoc.
     */
    @Bean
    public PulledGeolocationPort pulledGeolocationPort(VisionGeoVisualProperties properties,
                                                         ObjectProvider<ManagedChannel> cvGrpcChannel,
                                                         ObjectProvider<CvChannelSupervisor> cvChannelSupervisor) {
        if (properties.enabled()) {
            CvChannelSupervisor supervisor = cvChannelSupervisor.getObject();
            return new GrpcPulledGeolocationPort(cvGrpcChannel.getObject(), supervisor,
                    properties.mountPitchDegrees());
        }
        return new NoopGeolocationPort();
    }

    /**
     * Real {@link GrpcReferenceIndexPort} when enabled (sharing the same channel, D3), {@link
     * NoopReferenceIndexPort} otherwise — see class javadoc.
     */
    @Bean
    public ReferenceIndexPort referenceIndexPort(VisionGeoVisualProperties properties,
                                                   ObjectProvider<ManagedChannel> cvGrpcChannel) {
        if (properties.enabled()) {
            return new GrpcReferenceIndexPort(cvGrpcChannel.getObject(), properties.toGeoUploadSettings());
        }
        return new NoopReferenceIndexPort();
    }

    /**
     * Unconditional — {@code GeoRegionController} needs this bean regardless of the flag (see class
     * javadoc); its {@link ReferenceIndexPort}/{@link ReferenceTileSourcePort} collaborators are
     * themselves always-present, real-or-no-op per the two beans above.
     */
    @Bean
    public ReferenceRegionService referenceRegionService(ReferenceTileSourcePort referenceTileSourcePort,
                                                           ReferenceIndexPort referenceIndexPort,
                                                           VisionGeoVisualProperties properties) {
        return new DefaultReferenceRegionService(referenceTileSourcePort, referenceIndexPort,
                properties.toReferenceRegionSettings());
    }

    /**
     * Unconditional — needed by {@link VisualGeoRunner} (when present) and, indirectly, nothing in
     * {@code vision-api} today (no controller reaches perception's session service directly, only
     * through corrections/regions), but wired here regardless for the same "the service exists, only
     * the runner is gated" posture the rest of this class follows.
     */
    @Bean
    public GeolocationSessionService geolocationSessionService(PulledGeolocationPort pulledGeolocationPort) {
        return new DefaultGeolocationSessionService(pulledGeolocationPort);
    }

    /**
     * Unconditional — {@code GeoCorrectionController} needs this bean regardless of the flag (see
     * class javadoc). {@link TrackCorrectionRepositoryPort} (storage/persistence,
     * unconditional/Postgres-only) and {@link EventPublisherPort} (already unconditional in {@link
     * ApplicationServiceWiring}) are both already always-present; {@link
     * #trackCorrectionLiveUpdatePort} below is the sixth {@code LiveUpdateRegistry} selector, itself
     * always present (real when {@code vision.live.enabled=true}, no-op otherwise).
     */
    @Bean
    public TrackCorrectionService trackCorrectionService(TrackCorrectionRepositoryPort trackCorrectionRepositoryPort,
                                                           TrackCorrectionLiveUpdatePort trackCorrectionLiveUpdatePort,
                                                           EventPublisherPort eventPublisherPort,
                                                           VisionGeoVisualProperties properties) {
        return new DefaultTrackCorrectionService(trackCorrectionRepositoryPort, trackCorrectionLiveUpdatePort,
                eventPublisherPort, properties.toTrackCorrectionSettings());
    }

    /**
     * The one flag-gated bean in this class (D9) — present only when {@code
     * vision.geo.visual.enabled=true}. Absent, no tick ever runs, no gRPC geo session is ever opened,
     * and no {@code geo} SSE event is ever published, exactly as D9 requires.
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(prefix = "vision.geo.visual", name = "enabled", havingValue = "true")
    public VisualGeoRunner visualGeoRunner(AssetService assetService,
                                            AssetUsageRepositoryPort assetUsageRepositoryPort,
                                            StreamService streamService, UsageTracker usageTracker,
                                            GeolocationSessionService geolocationSessionService,
                                            TrackCorrectionService trackCorrectionService,
                                            TrackCorrectionRepositoryPort trackCorrectionRepositoryPort,
                                            VisionGeoVisualProperties properties) {
        return new VisualGeoRunner(assetService, assetUsageRepositoryPort, streamService, usageTracker,
                geolocationSessionService, trackCorrectionService, trackCorrectionRepositoryPort, properties);
    }
}

package com.drones.vision.app.config.wiring;

import com.drones.vision.api.support.FixedCameraGeoProperties;
import com.drones.vision.app.config.properties.VisionGeoProperties;
import com.drones.vision.app.geo.TrackProjectionRunner;
import com.drones.vision.map.application.LayerResolver;
import com.drones.vision.map.application.MapAccessPolicy;
import com.drones.vision.map.application.track.CameraPoseService;
import com.drones.vision.map.application.track.DefaultCameraPoseService;
import com.drones.vision.map.application.track.DefaultTrackProjectionService;
import com.drones.vision.map.application.track.TrackProjectionService;
import com.drones.vision.map.domain.port.CameraPoseRepositoryPort;
import com.drones.vision.map.domain.port.MapLiveUpdatePort;
import com.drones.vision.map.domain.port.TrackTrailRepositoryPort;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.perception.application.stream.StreamService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the fixed-camera geolocation feature (docs/plans/done/FIXED-CAMERA-GEO-PLAN.md §4/§8,
 * Wave G4) — a separate {@code @Configuration} from {@code ApplicationServiceWiring}/{@code
 * PersistenceWiringConfiguration}, kept its own file per that plan's hazard 5 ("{@code
 * station/vision-app} wiring ... frequently touched by other active branches — keep G4's wiring in
 * a new file rather than editing shared ones where possible").
 *
 * <p>{@link #cameraPoseService}/{@link #trackProjectionService} are wired unconditionally — {@code
 * CameraPoseController}/{@code MapTracksController} (vision-api, component-scanned) take them as
 * ordinary constructor dependencies regardless of the flag, exactly like {@code
 * OnboardingWiringConfiguration}'s {@code VehicleProfileService}/{@code RemediationService}/{@code
 * ReadinessService}. What the flag (D8) actually gates is {@link #trackProjectionRunner} — the only
 * thing that ever calls either service with real data — and every §5 endpoint's own {@code 409}
 * (checked at the vision-api edge via {@link FixedCameraGeoProperties#requireEnabled()}, not here).
 */
@Configuration
@EnableConfigurationProperties(VisionGeoProperties.class)
public class FixedCameraGeoWiringConfiguration {

    /**
     * The {@code vision-api}-side bridge {@link FixedCameraGeoProperties}'s own javadoc documents in
     * full: {@code vision-api} may not depend on this module's {@code @ConfigurationProperties}
     * type, so its plain framework-free mirror is populated here instead — the same bridge pattern
     * {@code OnboardingWiringConfiguration#onboardingApiProperties} already establishes.
     */
    @Bean
    public FixedCameraGeoProperties fixedCameraGeoApiProperties(VisionGeoProperties properties) {
        return new FixedCameraGeoProperties(properties.enabled(), properties.calibration().maxRmsErrorPixels());
    }

    /**
     * CRUD over one asset's camera pose (D4), audited — behind {@code CameraPoseController}
     * (vision-api, component-scanned).
     */
    @Bean
    public CameraPoseService cameraPoseService(CameraPoseRepositoryPort cameraPoseRepositoryPort,
                                                AuditTrailPort auditTrailPort) {
        return new DefaultCameraPoseService(cameraPoseRepositoryPort, auditTrailPort);
    }

    /**
     * Folds tracked objects into ground fixes and holds/publishes the live picture (D3) — behind
     * {@code MapTracksController} and fed each tick by {@link #trackProjectionRunner}. Takes {@link
     * MapLiveUpdatePort}/{@link MapAccessPolicy}/{@link LayerResolver} exactly as {@code
     * ApplicationServiceWiring}'s own {@code mapLayerService}/{@code markService} beans do — the same
     * three collaborators every map service composes, resolved from the beans that module already
     * declares.
     */
    @Bean
    public TrackProjectionService trackProjectionService(TrackTrailRepositoryPort trackTrailRepositoryPort,
                                                           MapLiveUpdatePort mapLiveUpdatePort,
                                                           MapAccessPolicy mapAccessPolicy,
                                                           LayerResolver layerResolver,
                                                           VisionGeoProperties properties) {
        return new DefaultTrackProjectionService(trackTrailRepositoryPort, mapLiveUpdatePort, mapAccessPolicy,
                layerResolver, properties.toTrackProjectionSettings());
    }

    /**
     * The scheduled composition (D2/D7) — present only when {@code
     * vision.geo.fixed-camera.enabled=true}; absent (the default) means no tick is ever scheduled,
     * and {@code CvWiring#detectionDemandPort}'s {@code ObjectProvider<TrackProjectionRunner>}
     * resolves to nothing, so its D9 predicate defaults to {@code assetId -> false} — byte-identical
     * demand behaviour to before this feature existed.
     *
     * <p>{@code initMethod = "start"} arms the tick loop as soon as this bean is constructed;
     * {@code destroyMethod = "close"} stops only this bean's own scheduler, mirroring {@code
     * CvWiring#cvChannelSupervisor}'s lifecycle idiom (this codebase's only other self-scheduled
     * bean).
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(prefix = "vision.geo.fixed-camera", name = "enabled", havingValue = "true")
    public TrackProjectionRunner trackProjectionRunner(AssetService assetService, StreamService streamService,
                                                         CameraPoseService cameraPoseService,
                                                         TrackProjectionService trackProjectionService,
                                                         VisionGeoProperties properties) {
        return new TrackProjectionRunner(assetService, streamService, cameraPoseService, trackProjectionService,
                properties);
    }
}

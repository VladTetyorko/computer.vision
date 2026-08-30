package com.drones.vision.app.config.wiring;

import com.drones.vision.api.support.StreamDetectionSupport;
import com.drones.vision.app.config.properties.VisionCvProperties;
import com.drones.vision.perception.application.profile.CvProfileCache;
import com.drones.vision.perception.application.profile.CvProfileCacheSettings;
import com.drones.vision.perception.application.profile.CvProfileResolver;
import com.drones.vision.perception.application.profile.CvProfileService;
import com.drones.vision.perception.application.profile.DefaultCvProfileService;
import com.drones.vision.perception.domain.port.CvProfileRepositoryPort;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.warehouse.application.asset.AssetService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires CV profiles and their scope bindings (docs/plans/active/CV-SETTINGS-PLAN.md §3.1/§5,
 * CV-SETTINGS-CONTEXT.md's W2 &rarr; W5 handoff) behind {@code CvProfileController} (vision-api,
 * component-scanned): {@code GET/POST/PUT/DELETE /api/cv/profiles}[/{id}], {@code
 * PUT/DELETE /api/cv/bindings}, {@code GET /api/cv/profiles/effective}, {@code GET /api/cv/coverage}.
 * {@link StreamDetectionSupport}/{@link com.drones.vision.perception.application.stream.DefaultStreamService}
 * (via {@link CvProfileResolver} — see {@code ApplicationServiceWiring#streamService}) also read
 * through this same {@link CvProfileService}/{@link CvProfileResolver}.
 *
 * <p><b>Unconditional, no feature flag</b> — every bean below is a plain {@code @Bean}, not gated by
 * {@code vision.cv.enabled}/{@code vision.cv.registry.enabled} or any property of its own: profiles
 * ship built-in (the four seeded rows {@code V29__cv_profiles.sql} inserts) regardless of whether
 * detection or the model registry are turned on — the same "ships built-in" posture {@code
 * TrackingWiring#cvTrackerRoster} takes for the tracker roster.
 *
 * <p>Split into its own {@code @Configuration} class rather than folded into {@link CvWiring} — same
 * "split out by concern" precedent as {@link DiscoveryWiringConfiguration}/{@link
 * PersistenceWiringConfiguration} (see either's own javadoc) — because unlike everything in {@link
 * CvWiring}, none of these beans is conditional on any {@code vision.cv.*} switch.
 */
@Configuration
public class CvProfileWiringConfiguration {

    /**
     * {@link CvProfileCache}'s lazy-reload TTL, from {@link VisionCvProperties.Profiles#cacheTtl()}
     * (default 60s).
     */
    @Bean
    public CvProfileCacheSettings cvProfileCacheSettings(VisionCvProperties cvProperties) {
        return new CvProfileCacheSettings(cvProperties.profiles().cacheTtl());
    }

    /**
     * The write-through, lazy-TTL-reload cache every profile read/write in this deployment goes
     * through — see that class's own javadoc for why (avoids a database round trip on the hot path,
     * {@code DefaultStreamService#start} resolves a profile on every stream start).
     */
    @Bean
    public CvProfileCache cvProfileCache(CvProfileRepositoryPort cvProfileRepositoryPort,
                                          CvProfileCacheSettings cvProfileCacheSettings) {
        return new CvProfileCache(cvProfileRepositoryPort, cvProfileCacheSettings);
    }

    /**
     * The asset &rarr; category &rarr; organization &rarr; platform fold — shared by {@link
     * #cvProfileService} (for {@code GET /api/cv/profiles/effective}/{@code GET /api/cv/coverage})
     * and {@code ApplicationServiceWiring#streamService} (for the asset-level/simulation start paths)
     * and {@code CvWiring#streamDetectionSupport} (for the device-level start path).
     */
    @Bean
    public CvProfileResolver cvProfileResolver(CvProfileCache cvProfileCache) {
        return new CvProfileResolver(cvProfileCache);
    }

    /**
     * Profile CRUD, binding, and the two resolved-config reads, behind {@code CvProfileController}
     * (vision-api, component-scanned) — a one-line assembly, mirroring {@code
     * TrainingWiringConfiguration#datasetService}'s shape. {@code assetService}/{@code
     * auditTrailPort} are already unconditionally wired in {@code ApplicationServiceWiring}.
     */
    @Bean
    public CvProfileService cvProfileService(CvProfileCache cvProfileCache, CvProfileResolver cvProfileResolver,
                                              AssetService assetService, AuditTrailPort auditTrailPort) {
        return new DefaultCvProfileService(cvProfileCache, cvProfileResolver, assetService, auditTrailPort);
    }
}

package com.drones.vision.app.config.wiring;

import com.drones.vision.api.support.afteraction.AfterActionAssembler;
import com.drones.vision.api.support.afteraction.AfterActionProperties;
import com.drones.vision.api.support.afteraction.AfterActionSources;
import com.drones.vision.app.config.properties.VisionApplicationProperties;
import com.drones.vision.events.application.ReplayService;
import com.drones.vision.flight.application.VehicleProfileService;
import com.drones.vision.map.application.mark.MarkService;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.warehouse.application.asset.AssetService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the after-action evidence package (docs/plans/done/AFTER-ACTION-PLAN.md) — a separate
 * {@code @Configuration} from {@code ApplicationServiceWiring}, same split-out-by-concern precedent
 * as {@link OnboardingWiringConfiguration}/{@link AuthWiringConfiguration}. Every collaborator
 * {@link AfterActionAssembler} needs already exists as a bean elsewhere in this module; this class
 * only bundles them (D1 — the assembler itself lives in {@code vision-api}, which already sees
 * every context).
 *
 * <p>No new flag: the plan states the feature ships on, since it exposes nothing that was not
 * already exposed elsewhere, only re-shaped.
 */
@Configuration
public class AfterActionWiringConfiguration {

    /**
     * {@code maxPoints} is a derived reference to the live-configured {@code
     * vision.application.replay.max-points-ceiling} value — the exact ceiling {@code ReplayService}
     * itself thins telemetry to ({@link ApplicationServiceWiring#replayService}) — so D7's
     * thinning-detection heuristic can never drift out of sync with the real ceiling, even if an
     * operator retunes it. {@code auditLimit} has no existing home to derive from; {@code
     * vision.after-action.audit-limit} (default 200) is this feature's own knob.
     */
    @Bean
    public AfterActionProperties afterActionProperties(VisionApplicationProperties applicationProperties,
                                                         @Value("${vision.after-action.audit-limit:200}") int auditLimit) {
        return new AfterActionProperties(applicationProperties.replay().maxPointsCeiling(), auditLimit);
    }

    @Bean
    public AfterActionSources afterActionSources(MarkService markService, VehicleProfileService vehicleProfileService,
                                                  AuditTrailPort auditTrailPort) {
        return new AfterActionSources(markService, vehicleProfileService, auditTrailPort);
    }

    @Bean
    public AfterActionAssembler afterActionAssembler(AssetService assetService, ReplayService replayService,
                                                       AfterActionSources afterActionSources,
                                                       AfterActionProperties afterActionProperties) {
        return new AfterActionAssembler(assetService, replayService, afterActionSources, afterActionProperties);
    }
}

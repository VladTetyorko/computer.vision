package com.drones.vision.app.config.wiring;

import com.drones.vision.api.support.OnboardingProperties;
import com.drones.vision.api.support.RemediationOrchestrator;
import com.drones.vision.app.config.properties.VisionOnboardingProperties;
import com.drones.vision.app.devsupport.NoopVehicleConfigPort;
import com.drones.vision.flight.application.DefaultReadinessService;
import com.drones.vision.flight.application.DefaultRemediationService;
import com.drones.vision.flight.application.DefaultVehicleProfileService;
import com.drones.vision.flight.application.ReadinessService;
import com.drones.vision.flight.application.RemediationService;
import com.drones.vision.flight.application.VehicleProfileService;
import com.drones.vision.flight.domain.port.FeatureRequirementRepositoryPort;
import com.drones.vision.flight.domain.port.VehicleConfigPort;
import com.drones.vision.flight.domain.port.VehicleProfileRepositoryPort;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.domain.port.AssetLiveStatePort;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the vehicle-onboarding pipeline's control plane (docs/plans/active/DRONE-ONBOARDING-PLAN.md
 * O5) — a separate {@code @Configuration} from {@code ApplicationServiceWiring}, same
 * split-out-by-concern precedent as {@link AuthWiringConfiguration}/{@link
 * PersistenceWiringConfiguration}.
 *
 * <p>{@link VehicleProfileService}/{@link RemediationService}/{@link ReadinessService} are wired
 * unconditionally — they exist and behave the same regardless of the flag, exactly like {@code
 * AuthWiringConfiguration}'s {@code AuthService}/{@code UserService}. What the flag actually gates
 * is {@link VehicleConfigPort}: {@link #noopVehicleConfigPort} when {@code
 * vision.onboarding.probe.enabled} is {@code false} (the default, D17 — with it off the system
 * behaves exactly as it does today) or absent. <strong>No bean at all when the flag is {@code
 * true}</strong>: this wave's file scope ({@code station/vision-api}, {@code station/vision-app},
 * {@code storage/persistence} only) cannot construct {@code adapter-mavlink}'s {@code
 * MavlinkVehicleConfigurator} (O4, a separate, concurrent wave), and a silent Noop fallback under a
 * flag an operator explicitly turned <em>on</em> would be exactly the "probably fine, didn't check"
 * lie C7 forbids — see {@link NoopVehicleConfigPort}'s own javadoc. Flipping the flag on before O4
 * lands must fail application startup (no {@code VehicleConfigPort} bean to satisfy {@link
 * VehicleProfileService}'s/{@link RemediationService}'s constructors), not fail quietly at request
 * time. <strong>Flagged for whoever wires O4</strong>: that wave needs to add the mirror-image
 * {@code @ConditionalOnProperty(havingValue = "true")} bean constructing {@code
 * MavlinkVehicleConfigurator} here (or in its own wiring class) before the flag can safely go live
 * anywhere.
 */
@Configuration
@EnableConfigurationProperties(VisionOnboardingProperties.class)
public class OnboardingWiringConfiguration {

    /**
     * Flag off (default) or absent: no real vehicle link exists to probe, so every {@code
     * VehicleConfigPort} call refuses honestly rather than silently doing nothing that looks like
     * success. See {@link NoopVehicleConfigPort}'s own javadoc for why its refusal message is the
     * §8.1-frozen wire text.
     */
    @Bean
    @ConditionalOnProperty(prefix = "vision.onboarding.probe", name = "enabled", havingValue = "false",
            matchIfMissing = true)
    public VehicleConfigPort noopVehicleConfigPort() {
        return new NoopVehicleConfigPort();
    }

    /** The PROBE stage (docs/plans/active/DRONE-ONBOARDING-PLAN.md §3.1) — behind {@code OnboardingController}. */
    @Bean
    public VehicleProfileService vehicleProfileService(AssetService assetService, VehicleConfigPort vehicleConfigPort,
                                                         VehicleProfileRepositoryPort vehicleProfileRepositoryPort,
                                                         AuditTrailPort auditTrailPort) {
        return new DefaultVehicleProfileService(assetService, vehicleConfigPort, vehicleProfileRepositoryPort,
                auditTrailPort);
    }

    /**
     * The CONFIGURE stage's vehicle-side half (docs/plans/active/DRONE-ONBOARDING-PLAN.md §3.1,
     * Mechanism A + Tier-A/B {@code PARAM_SET}) — behind {@code OnboardingController}.
     */
    @Bean
    public RemediationService remediationService(AssetService assetService, AssetLiveStatePort assetLiveStatePort,
                                                  VehicleConfigPort vehicleConfigPort,
                                                  AuditTrailPort auditTrailPort) {
        return new DefaultRemediationService(assetService, assetLiveStatePort, vehicleConfigPort, auditTrailPort);
    }

    /** The NEGOTIATE stage (docs/plans/active/DRONE-ONBOARDING-PLAN.md §3.1) — behind {@code ReadinessController}. */
    @Bean
    public ReadinessService readinessService(AssetService assetService,
                                              VehicleProfileRepositoryPort vehicleProfileRepositoryPort,
                                              FeatureRequirementRepositoryPort featureRequirementRepositoryPort) {
        return new DefaultReadinessService(assetService, vehicleProfileRepositoryPort,
                featureRequirementRepositoryPort);
    }

    /**
     * The {@code vision-api}-side bridge {@link OnboardingProperties}'s own javadoc documents in
     * full: {@code vision-api} may not depend on this module's {@code @ConfigurationProperties}
     * type, so its plain framework-free mirror is populated here instead, exactly like {@code
     * PublishWiring#snapshotJpegEncoder}'s existing {@code VisionApiProperties} bridge.
     */
    @Bean
    public OnboardingProperties onboardingApiProperties(VisionOnboardingProperties properties) {
        return new OnboardingProperties(properties.probe().inventoryWindow(), properties.probe().requestTimeout());
    }

    /**
     * Composes the CONFIGURE stage's {@code {features, actions}} wire request into dispatched
     * {@link RemediationService} calls plus a re-probe — see {@link RemediationOrchestrator}'s own
     * javadoc for why this composition lives in {@code vision-api} rather than as a fourth {@code
     * vision-flight} application service (a plan gap, flagged for whoever next touches that
     * context).
     */
    @Bean
    public RemediationOrchestrator remediationOrchestrator(FeatureRequirementRepositoryPort featureRequirementRepositoryPort,
                                                             RemediationService remediationService,
                                                             VehicleProfileService vehicleProfileService,
                                                             ReadinessService readinessService,
                                                             OnboardingProperties onboardingApiProperties) {
        return new RemediationOrchestrator(featureRequirementRepositoryPort, remediationService, vehicleProfileService,
                readinessService, onboardingApiProperties);
    }
}

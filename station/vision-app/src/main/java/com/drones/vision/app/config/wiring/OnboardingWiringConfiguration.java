package com.drones.vision.app.config.wiring;

import com.drones.vision.adapter.mavlink.MavlinkTelemetrySource;
import com.drones.vision.adapter.mavlink.MavlinkVehicleConfigurator;
import com.drones.vision.api.support.OnboardingProperties;
import com.drones.vision.api.support.RemediationOrchestrator;
import com.drones.vision.app.config.properties.VisionMavlinkProperties;
import com.drones.vision.app.config.properties.VisionOnboardingProperties;
import com.drones.vision.app.config.properties.VisionRcProperties;
import com.drones.vision.app.devsupport.NoopVehicleConfigPort;
import com.drones.vision.app.onboarding.PassportCaptureObserver;
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
import com.drones.vision.warehouse.application.usage.UsageSessionService;
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
 * behaves exactly as it does today) or absent, and {@link #mavlinkVehicleConfigurator} — O4's real
 * implementation, talking to actual firmware — when it is {@code true}. A silent Noop fallback under
 * a flag an operator explicitly turned <em>on</em> would be exactly the "probably fine, didn't check"
 * lie C7 forbids, so the two beans are mutually exclusive by condition rather than by ordering: see
 * {@link NoopVehicleConfigPort}'s own javadoc.
 *
 * <p>O5 shipped only the {@code false} branch, because its file scope could not construct {@code
 * adapter-mavlink}'s configurator while O4 was still in flight; turning the flag on then failed
 * startup outright for want of a bean. That is no longer the trade — O4 has landed, so the flag now
 * does what an operator reading it would expect.
 */
@Configuration
@EnableConfigurationProperties(VisionOnboardingProperties.class)
public class OnboardingWiringConfiguration {

    private static final System.Logger LOG = System.getLogger(OnboardingWiringConfiguration.class.getName());

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

    /**
     * Flag on: the real thing (O4). Borrows {@link TelemetryWiring#mavlinkTelemetrySource}'s
     * gateways rather than opening sockets of its own — a probe of an already-registered aircraft
     * must go out over the very link that aircraft is already talking on, and
     * {@code MavlinkVehicleConfigurator} will only ever close a gateway it opened itself.
     *
     * <p>Declared as {@link VehicleConfigPort}, not as the concrete type: nothing in this module
     * should be able to reach past the port to MAVLink-specific behaviour, and only one of these two
     * beans exists at a time.
     */
    @Bean
    @ConditionalOnProperty(prefix = "vision.onboarding.probe", name = "enabled", havingValue = "true")
    public VehicleConfigPort mavlinkVehicleConfigurator(MavlinkTelemetrySource mavlinkTelemetrySource,
                                                          VisionMavlinkProperties mavlinkProperties,
                                                          VisionRcProperties rcProperties,
                                                          VisionOnboardingProperties onboardingProperties) {
        return new MavlinkVehicleConfigurator(mavlinkTelemetrySource,
                TelemetryWiring.toMavlinkSettings(mavlinkProperties, rcProperties, onboardingProperties));
    }

    /**
     * The PROBE stage (docs/plans/active/DRONE-ONBOARDING-PLAN.md §3.1) — behind {@code
     * OnboardingController}. Takes {@link UsageSessionService} (docs/plans/active/
     * ARCHITECTURE-AUDIT-2026-08-26.md R5, O11 originally) because a flight passport must resolve
     * for an <em>old</em> flight: membership is proven from the usage's own {@code assetId} via
     * {@link UsageSessionService#usageBelongsToAsset}, uncapped, rather than from the asset's capped
     * recent-usages window.
     */
    @Bean
    public VehicleProfileService vehicleProfileService(AssetService assetService, VehicleConfigPort vehicleConfigPort,
                                                         VehicleProfileRepositoryPort vehicleProfileRepositoryPort,
                                                         UsageSessionService usageSessionService,
                                                         AuditTrailPort auditTrailPort) {
        return new DefaultVehicleProfileService(assetService, vehicleConfigPort, vehicleProfileRepositoryPort,
                usageSessionService, auditTrailPort);
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

    /**
     * The flight passport's automatic capture (docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.4,
     * Wave O11) — wired as vision-perception's {@code UsagePhaseObserver} seam in {@link
     * ApplicationServiceWiring#usageTracker}, so a usage's PREFLIGHT/POSTFLIGHT phase transition
     * triggers {@link VehicleProfileService#captureSnapshot} without {@code UsageTracker} ever
     * depending on the flight context (the same {@code BiConsumer} composition role this class
     * already plays for {@code geofenceMonitor::evaluate}).
     *
     * <p><b>Absent (no bean, no capture ever attempted) unless {@code
     * vision.onboarding.passport.enabled} is {@code true}</b> (default {@code false}) — {@code
     * ApplicationServiceWiring#usageTracker} falls back to {@code UsagePhaseObserver#NOOP} via its
     * {@code ObjectProvider} when this bean is missing, so the default-config build folds a usage's
     * phase exactly as it did before O11 wired anything up.
     *
     * <p><b>Capturing a passport needs {@code vision.onboarding.probe.enabled} too</b> to do
     * anything real: with probing off, {@link VehicleConfigPort} is {@link NoopVehicleConfigPort},
     * which has no probeable device by construction, so every capture attempt fails with the
     * expected, low-level-logged {@link IllegalStateException} {@link PassportCaptureObserver}'s
     * own javadoc documents — forever, silently (from an operator's point of view) unless something
     * says so louder. Turning the passport flag on while probing stays off is exactly that trap, so
     * this method logs one clear {@code WARNING} at startup naming both flags — the honesty C7
     * demands, not a silent no-op.
     */
    @Bean
    @ConditionalOnProperty(prefix = "vision.onboarding.passport", name = "enabled", havingValue = "true")
    public PassportCaptureObserver passportCaptureObserver(VehicleProfileService vehicleProfileService,
                                                             VisionOnboardingProperties properties) {
        if (!properties.probe().enabled()) {
            LOG.log(System.Logger.Level.WARNING, "vision.onboarding.passport.enabled=true but "
                    + "vision.onboarding.probe.enabled=false -- every flight passport capture will fail "
                    + "(no device this platform can probe) until probing is also enabled");
        }
        return new PassportCaptureObserver(vehicleProfileService, properties.probe().inventoryWindow());
    }
}

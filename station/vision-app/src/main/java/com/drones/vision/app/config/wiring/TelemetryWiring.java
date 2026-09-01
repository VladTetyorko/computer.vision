package com.drones.vision.app.config.wiring;

import com.drones.vision.adapter.mavlink.MavlinkFlightCommander;
import com.drones.vision.adapter.mavlink.MavlinkManualControlSender;
import com.drones.vision.adapter.mavlink.MavlinkSettings;
import com.drones.vision.adapter.mavlink.MavlinkTelemetrySource;
import com.drones.vision.adapter.simulation.SimulatedTelemetrySource;
import com.drones.vision.adapter.simulation.TelemetrySettings;
import com.drones.vision.app.config.properties.VisionMavlinkProperties;
import com.drones.vision.app.config.properties.VisionOnboardingProperties;
import com.drones.vision.app.config.properties.VisionRcProperties;
import com.drones.vision.app.config.properties.VisionSimulationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Wires every {@code TelemetrySourcePort}/command-TX adapter for MAVLink and the synthetic
 * simulation source — the telemetry slice of what used to be one 825-line {@code
 * WiringConfiguration} (docs/plans/active/LAYERING-REFACTOR-PLAN.md wave D). Config extraction (wave F2):
 * {@link #mavlinkTelemetrySource}/{@link #mavlinkFlightCommander}/{@link
 * #mavlinkManualControlSender} each now take a {@code MavlinkSettings}/{@code MavlinkSettings.Rc}
 * built from {@code vision.mavlink.*}/{@code vision.rc.*} instead of the adapter's own no-arg
 * constructor — every mapped default is byte-identical to the literal it replaced.
 */
@Configuration
@EnableConfigurationProperties({VisionSimulationProperties.class, VisionMavlinkProperties.class,
        VisionRcProperties.class, VisionOnboardingProperties.class})
public class TelemetryWiring {

    /**
     * Synthetic 1&nbsp;Hz {@link com.drones.vision.flight.domain.port.TelemetrySourcePort}, collected
     * (alongside any other registered {@code TelemetrySourcePort} beans) into {@code
     * ApplicationServiceWiring#usageTracker}'s {@code List<TelemetrySourcePort>} so a {@code sim}
     * telemetry-capable device produces a demoable usage trail with zero hardware.
     */
    @Bean
    public SimulatedTelemetrySource simulatedTelemetrySource(VisionSimulationProperties properties) {
        VisionSimulationProperties.Telemetry telemetry = properties.telemetry();
        return new SimulatedTelemetrySource(new TelemetrySettings(telemetry.centerLatitude(),
                telemetry.centerLongitude(), telemetry.trackRadiusMeters(), telemetry.period().toMillis(),
                telemetry.batteryDrainPercentPerSecond()));
    }

    /**
     * RX half of the MAVLink TX/RX pair (docs/plans/done/MVP2-PLAN.md X-a): ingests MAVLink 2 telemetry over
     * UDP — from a real telemetry radio, ArduPilot/PX4 SITL, or {@code
     * FeedTransmitterWiring#mavlinkFeedTransmitter} — for {@code "mavlink"}-protocol telemetry
     * devices. Collected (alongside {@link #simulatedTelemetrySource} and any other registered
     * {@code TelemetrySourcePort} beans) into {@code ApplicationServiceWiring#usageTracker}'s
     * {@code List<TelemetrySourcePort>}.
     */
    @Bean
    public MavlinkTelemetrySource mavlinkTelemetrySource(VisionMavlinkProperties mavlinkProperties,
                                                           VisionRcProperties rcProperties,
                                                           VisionOnboardingProperties onboardingProperties) {
        return new MavlinkTelemetrySource(toMavlinkSettings(mavlinkProperties, rcProperties, onboardingProperties));
    }

    /**
     * Maps {@link VisionMavlinkProperties} (plus {@link VisionRcProperties} for the mandatory
     * {@link MavlinkSettings#rc()} slice and {@link VisionOnboardingProperties} for the
     * {@link MavlinkSettings#onboarding()} one) onto a full {@code MavlinkSettings} — shared with
     * {@code FeedTransmitterWiring#mavlinkFeedTransmitter}, which needs the identical mapping.
     *
     * <p>The onboarding slice is deliberately assembled from {@code vision.onboarding.*} rather than
     * {@code vision.mavlink.*}: an operator reasons about onboarding as one feature with one set of
     * guardrails, not as a MAVLink tuning knob that happens to live next to socket timeouts.
     */
    static MavlinkSettings toMavlinkSettings(VisionMavlinkProperties properties, VisionRcProperties rcProperties,
                                              VisionOnboardingProperties onboardingProperties) {
        VisionMavlinkProperties.Scan scan = properties.scan();
        VisionMavlinkProperties.Transmit transmit = properties.transmit();
        return new MavlinkSettings(properties.bindHost(), properties.silenceWindow(),
                properties.maxUnclaimedVehicles(), properties.closeJoinTimeout(), properties.ackTimeout(),
                new MavlinkSettings.Scan(scan.activeHubPollCount(), scan.activeHubMinPollInterval(),
                        scan.selfBindMinReadTimeout(), scan.selfBindMaxReadTimeout()),
                new MavlinkSettings.Transmit(transmit.tick(), transmit.heartbeatPeriod(), transmit.defaultSpeedMps(),
                        transmit.defaultPositionRateHz(), transmit.defaultFailsafeBatteryPercent(),
                        transmit.defaultSysid()),
                new MavlinkSettings.Rc(rcProperties.overrideHz(), rcProperties.minOverrideHz(),
                        rcProperties.maxOverrideHz(), rcProperties.releaseFrames()))
                .withCommandRetries(properties.commandRetries())
                .withOnboarding(toOnboarding(onboardingProperties))
                .withLinkStatus(new MavlinkSettings.LinkStatus(properties.dropRateWarnPercent(),
                        properties.dropRateAlarmPercent(), properties.linkFailureGrace()));
    }

    /**
     * Overrides the on-connect flag, the per-request timeout that
     * {@code vision.onboarding.probe.request-timeout} exists to set, and — when the operator supplies
     * one — the probe parameter list.
     *
     * <p>An empty {@code vision.onboarding.probe.parameters} keeps the firmware-verified defaults,
     * which is what almost every deployment wants. It is a property rather than a constant because
     * parameter names are firmware-version state and vary by vehicle type: {@code FENCE_ALT_MAX}
     * exists on Copter and not on Rover, and the system id is spelled differently either side of
     * ArduPilot 4.7. The javadoc on {@link MavlinkSettings.Onboarding#defaults()} claimed this was
     * already configuration while this method hard-wired the defaults — see
     * docs/plans/active/FLEET-RADIO-PLAN.md F12.
     *
     * <p>The risk the previous comment named is real and unchanged: a stale override degrades the
     * probe silently rather than failing, because MAVLink cannot report an unknown parameter name.
     * That argues for a good default, which this keeps, not for refusing to let a fleet correct it.
     */
    private static MavlinkSettings.Onboarding toOnboarding(VisionOnboardingProperties properties) {
        MavlinkSettings.Onboarding defaults = MavlinkSettings.Onboarding.defaults();
        List<String> configured = properties.probe().parameters();
        List<String> probeParameters = configured.isEmpty() ? defaults.probeParameters() : configured;
        return new MavlinkSettings.Onboarding(probeParameters,
                properties.probe().requestTimeout(), defaults.capabilityRetries(),
                properties.probe().requestTimeout(), defaults.parameterRetries(),
                properties.remediate().messageInterval().enabled(),
                defaults.onConnectMessageRequests());
    }

    /**
     * The one deliberate command-TX path (docs/plans/active/DRONE-INFRA-PLAN.md I-e, Stage 1 — "bring it
     * home"): sends {@code MAV_CMD_DO_SET_MODE} return-to-home reusing the exact same shared
     * socket {@link #mavlinkTelemetrySource} already has open for RX, rather than opening a second
     * one of its own. {@link #mavlinkTelemetrySource} is wired unconditionally, so this bean is too
     * — there is no {@code vision.mavlink.*}-shaped gate.
     *
     * <p><b>(MAVLINK-COMMANDS-PLAN P4)</b> Now built on {@link MavlinkFlightCommander}'s canonical
     * {@code (MavlinkTelemetrySource, MavlinkSettings)} constructor, reusing {@link
     * #toMavlinkSettings} exactly like {@link #mavlinkTelemetrySource} does, instead of the 2-arg
     * {@code (MavlinkTelemetrySource, Duration)} back-compat overload. Closes the production gap P1
     * documented (drone-link/mavlink's own MODULE.md Gotchas): that overload passed only {@code
     * properties.ackTimeout()} and always defaulted {@code commandRetries} to {@code
     * MavlinkSettings.defaults()}'s value, so a deployment got the new bounded-retry behaviour
     * layered onto the *old* 2s per-attempt timeout (worst case ~6s for a silent vehicle) rather than
     * the ~2.1s {@code vision.mavlink.command-retries}/{@code ack-timeout} were designed to bound
     * together. {@code toMavlinkSettings} threads both {@link VisionMavlinkProperties#ackTimeout()}
     * (now defaulting to 700ms) and {@link VisionMavlinkProperties#commandRetries()} (default 2)
     * through {@code MavlinkSettings.withCommandRetries}, the same seam {@link #mavlinkTelemetrySource}
     * relies on.
     */
    @Bean
    public MavlinkFlightCommander mavlinkFlightCommander(MavlinkTelemetrySource mavlinkTelemetrySource,
                                                          VisionMavlinkProperties mavlinkProperties,
                                                          VisionRcProperties rcProperties,
                                                          VisionOnboardingProperties onboardingProperties) {
        return new MavlinkFlightCommander(mavlinkTelemetrySource,
                toMavlinkSettings(mavlinkProperties, rcProperties, onboardingProperties));
    }

    /**
     * Streaming, ack-less RC-override relay TX (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md §3, R3) — the
     * concrete {@code ManualControlPort} {@code ApplicationServiceWiring#manualControlService}
     * resolves by interface. Borrows {@link #mavlinkTelemetrySource}'s shared hub socket exactly
     * like {@link #mavlinkFlightCommander} does. {@code rcProperties} supplies the override-Hz/
     * release-frame cadence (docs/plans/active/LAYERING-REFACTOR-PLAN.md wave F2) that used to come from the
     * now-deleted {@code VISION_RC_OVERRIDE_HZ}/{@code VISION_RC_RELEASE_FRAMES} env vars.
     */
    @Bean
    public MavlinkManualControlSender mavlinkManualControlSender(MavlinkTelemetrySource mavlinkTelemetrySource,
                                                                  VisionRcProperties rcProperties) {
        return new MavlinkManualControlSender(mavlinkTelemetrySource,
                new MavlinkSettings.Rc(rcProperties.overrideHz(), rcProperties.minOverrideHz(),
                        rcProperties.maxOverrideHz(), rcProperties.releaseFrames()));
    }
}

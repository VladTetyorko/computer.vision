package com.drones.vision.app.config.wiring;

import com.drones.vision.adapter.mavlink.MavlinkFlightCommander;
import com.drones.vision.adapter.mavlink.MavlinkManualControlSender;
import com.drones.vision.adapter.mavlink.MavlinkSettings;
import com.drones.vision.adapter.mavlink.MavlinkTelemetrySource;
import com.drones.vision.adapter.simulation.SimulatedTelemetrySource;
import com.drones.vision.adapter.simulation.TelemetrySettings;
import com.drones.vision.app.config.properties.VisionMavlinkProperties;
import com.drones.vision.app.config.properties.VisionRcProperties;
import com.drones.vision.app.config.properties.VisionSimulationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires every {@code TelemetrySourcePort}/command-TX adapter for MAVLink and the synthetic
 * simulation source — the telemetry slice of what used to be one 825-line {@code
 * WiringConfiguration} (docs/LAYERING-REFACTOR-PLAN.md wave D). Config extraction (wave F2):
 * {@link #mavlinkTelemetrySource}/{@link #mavlinkFlightCommander}/{@link
 * #mavlinkManualControlSender} each now take a {@code MavlinkSettings}/{@code MavlinkSettings.Rc}
 * built from {@code vision.mavlink.*}/{@code vision.rc.*} instead of the adapter's own no-arg
 * constructor — every mapped default is byte-identical to the literal it replaced.
 */
@Configuration
@EnableConfigurationProperties({VisionSimulationProperties.class, VisionMavlinkProperties.class,
        VisionRcProperties.class})
public class TelemetryWiring {

    /**
     * Synthetic 1&nbsp;Hz {@link com.drones.vision.domain.port.out.TelemetrySourcePort}, collected
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
     * RX half of the MAVLink TX/RX pair (docs/MVP2-PLAN.md X-a): ingests MAVLink 2 telemetry over
     * UDP — from a real telemetry radio, ArduPilot/PX4 SITL, or {@code
     * FeedTransmitterWiring#mavlinkFeedTransmitter} — for {@code "mavlink"}-protocol telemetry
     * devices. Collected (alongside {@link #simulatedTelemetrySource} and any other registered
     * {@code TelemetrySourcePort} beans) into {@code ApplicationServiceWiring#usageTracker}'s
     * {@code List<TelemetrySourcePort>}.
     */
    @Bean
    public MavlinkTelemetrySource mavlinkTelemetrySource(VisionMavlinkProperties mavlinkProperties,
                                                           VisionRcProperties rcProperties) {
        return new MavlinkTelemetrySource(toMavlinkSettings(mavlinkProperties, rcProperties));
    }

    /**
     * Maps {@link VisionMavlinkProperties} (plus {@link VisionRcProperties} for the mandatory
     * {@link MavlinkSettings#rc()} slice) onto a full {@code MavlinkSettings} — shared with {@code
     * FeedTransmitterWiring#mavlinkFeedTransmitter}, which needs the identical mapping.
     */
    static MavlinkSettings toMavlinkSettings(VisionMavlinkProperties properties, VisionRcProperties rcProperties) {
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
                        rcProperties.maxOverrideHz(), rcProperties.releaseFrames()));
    }

    /**
     * The one deliberate command-TX path (docs/DRONE-INFRA-PLAN.md I-e, Stage 1 — "bring it
     * home"): sends {@code MAV_CMD_DO_SET_MODE} return-to-home reusing the exact same shared
     * socket {@link #mavlinkTelemetrySource} already has open for RX, rather than opening a second
     * one of its own. {@link #mavlinkTelemetrySource} is wired unconditionally, so this bean is too
     * — there is no {@code vision.mavlink.*}-shaped gate.
     */
    @Bean
    public MavlinkFlightCommander mavlinkFlightCommander(MavlinkTelemetrySource mavlinkTelemetrySource,
                                                          VisionMavlinkProperties properties) {
        return new MavlinkFlightCommander(mavlinkTelemetrySource, properties.ackTimeout());
    }

    /**
     * Streaming, ack-less RC-override relay TX (docs/RC-CONTROL-PHASE1-PLAN.md §3, R3) — the
     * concrete {@code ManualControlPort} {@code ApplicationServiceWiring#manualControlService}
     * resolves by interface. Borrows {@link #mavlinkTelemetrySource}'s shared hub socket exactly
     * like {@link #mavlinkFlightCommander} does. {@code rcProperties} supplies the override-Hz/
     * release-frame cadence (docs/LAYERING-REFACTOR-PLAN.md wave F2) that used to come from the
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

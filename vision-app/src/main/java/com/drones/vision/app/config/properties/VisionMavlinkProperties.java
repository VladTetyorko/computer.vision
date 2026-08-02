package com.drones.vision.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuration for {@code adapter-mavlink}'s RX/TX ({@code vision.mavlink.*}), docs/LAYERING-REFACTOR-PLAN.md
 * &sect;2.2, wave F2.
 *
 * <p>Mapped by {@code wiring.TelemetryWiring}/{@code wiring.FeedTransmitterWiring} onto {@code
 * com.drones.vision.adapter.mavlink.MavlinkSettings} (minus {@code Rc}, which is {@code vision.rc},
 * see {@link VisionRcProperties}) — every {@code @DefaultValue} below is byte-identical to {@code
 * MavlinkSettings.defaults()}'s own literal.
 *
 * @param bindHost             local bind address fallback for a device whose stream URI omits a
 *                             host; default {@value #DEFAULT_BIND_HOST}
 * @param silenceWindow        how long an unpinned claim may go silent before it may re-elect;
 *                             default 30s
 * @param maxUnclaimedVehicles bound on the unclaimed-vehicle registry; default {@value
 *                             #DEFAULT_MAX_UNCLAIMED_VEHICLES}
 * @param closeJoinTimeout     bound on a shared hub/feed's close-thread join; default 5s
 * @param ackTimeout           how long a flight command waits for a {@code COMMAND_ACK}; default 2s
 * @param scan                 {@code MavlinkHeartbeatScanner}'s poll/self-bind-timeout budget;
 *                             defaulted as a whole when absent
 * @param transmit             {@code MavlinkFeedTransmitter}'s cadence/defaults; defaulted as a
 *                             whole when absent
 */
@ConfigurationProperties(prefix = "vision.mavlink")
public record VisionMavlinkProperties(
        @DefaultValue(VisionMavlinkProperties.DEFAULT_BIND_HOST) String bindHost,
        @DefaultValue("30s") Duration silenceWindow,
        @DefaultValue(VisionMavlinkProperties.DEFAULT_MAX_UNCLAIMED_VEHICLES) int maxUnclaimedVehicles,
        @DefaultValue("5s") Duration closeJoinTimeout,
        @DefaultValue("2s") Duration ackTimeout,
        Scan scan,
        Transmit transmit) {

    static final String DEFAULT_BIND_HOST = "0.0.0.0";
    static final String DEFAULT_MAX_UNCLAIMED_VEHICLES = "32";

    public VisionMavlinkProperties {
        if (scan == null) {
            scan = new Scan(Scan.DEFAULT_ACTIVE_HUB_POLL_COUNT_INT, Scan.DEFAULT_ACTIVE_HUB_MIN_POLL_INTERVAL_DURATION,
                    Scan.DEFAULT_SELF_BIND_MIN_READ_TIMEOUT_DURATION, Scan.DEFAULT_SELF_BIND_MAX_READ_TIMEOUT_DURATION);
        }
        if (transmit == null) {
            transmit = new Transmit(Transmit.DEFAULT_TICK_DURATION, Transmit.DEFAULT_HEARTBEAT_PERIOD_DURATION,
                    Transmit.DEFAULT_DEFAULT_SPEED_MPS_DOUBLE, Transmit.DEFAULT_DEFAULT_POSITION_RATE_HZ_DOUBLE,
                    Transmit.DEFAULT_DEFAULT_FAILSAFE_BATTERY_PERCENT_DOUBLE, Transmit.DEFAULT_DEFAULT_SYSID_INT);
        }
    }

    /**
     * @param activeHubPollCount        how many times the hub-borrow discovery path samples the
     *                                  claimed/unclaimed registries; default {@value
     *                                  Scan#DEFAULT_ACTIVE_HUB_POLL_COUNT}
     * @param activeHubMinPollInterval  floor on the poll interval; default 20ms
     * @param selfBindMinReadTimeout    self-bind discovery path's minimum {@code SO_TIMEOUT}; default 20ms
     * @param selfBindMaxReadTimeout    self-bind discovery path's maximum {@code SO_TIMEOUT}; default 200ms
     */
    public record Scan(@DefaultValue(Scan.DEFAULT_ACTIVE_HUB_POLL_COUNT) int activeHubPollCount,
                        @DefaultValue("20ms") Duration activeHubMinPollInterval,
                        @DefaultValue("20ms") Duration selfBindMinReadTimeout,
                        @DefaultValue("200ms") Duration selfBindMaxReadTimeout) {

        static final String DEFAULT_ACTIVE_HUB_POLL_COUNT = "5";
        static final int DEFAULT_ACTIVE_HUB_POLL_COUNT_INT = 5;
        static final Duration DEFAULT_ACTIVE_HUB_MIN_POLL_INTERVAL_DURATION = Duration.ofMillis(20);
        static final Duration DEFAULT_SELF_BIND_MIN_READ_TIMEOUT_DURATION = Duration.ofMillis(20);
        static final Duration DEFAULT_SELF_BIND_MAX_READ_TIMEOUT_DURATION = Duration.ofMillis(200);
    }

    /**
     * @param tick                          synthetic-flight transmit tick period; default 50ms
     * @param heartbeatPeriod               {@code HEARTBEAT}/{@code SYS_STATUS}/{@code GPS_RAW_INT}
     *                                      cadence; default 1s
     * @param defaultSpeedMps               default cruise speed along the route; default {@value
     *                                      Transmit#DEFAULT_DEFAULT_SPEED_MPS}
     * @param defaultPositionRateHz         default {@code GLOBAL_POSITION_INT} send rate; default
     *                                      {@value Transmit#DEFAULT_DEFAULT_POSITION_RATE_HZ}
     * @param defaultFailsafeBatteryPercent default failsafe battery threshold; default {@value
     *                                      Transmit#DEFAULT_DEFAULT_FAILSAFE_BATTERY_PERCENT}
     * @param defaultSysid                  default MAVLink system id a feed transmits as; default
     *                                      {@value Transmit#DEFAULT_DEFAULT_SYSID}
     */
    public record Transmit(@DefaultValue("50ms") Duration tick,
                            @DefaultValue("1s") Duration heartbeatPeriod,
                            @DefaultValue(Transmit.DEFAULT_DEFAULT_SPEED_MPS) double defaultSpeedMps,
                            @DefaultValue(Transmit.DEFAULT_DEFAULT_POSITION_RATE_HZ) double defaultPositionRateHz,
                            @DefaultValue(Transmit.DEFAULT_DEFAULT_FAILSAFE_BATTERY_PERCENT) double defaultFailsafeBatteryPercent,
                            @DefaultValue(Transmit.DEFAULT_DEFAULT_SYSID) int defaultSysid) {

        static final String DEFAULT_DEFAULT_SPEED_MPS = "12.0";
        static final String DEFAULT_DEFAULT_POSITION_RATE_HZ = "5.0";
        static final String DEFAULT_DEFAULT_FAILSAFE_BATTERY_PERCENT = "15.0";
        static final String DEFAULT_DEFAULT_SYSID = "1";
        static final Duration DEFAULT_TICK_DURATION = Duration.ofMillis(50);
        static final Duration DEFAULT_HEARTBEAT_PERIOD_DURATION = Duration.ofSeconds(1);
        static final double DEFAULT_DEFAULT_SPEED_MPS_DOUBLE = 12.0;
        static final double DEFAULT_DEFAULT_POSITION_RATE_HZ_DOUBLE = 5.0;
        static final double DEFAULT_DEFAULT_FAILSAFE_BATTERY_PERCENT_DOUBLE = 15.0;
        static final int DEFAULT_DEFAULT_SYSID_INT = 1;
    }
}

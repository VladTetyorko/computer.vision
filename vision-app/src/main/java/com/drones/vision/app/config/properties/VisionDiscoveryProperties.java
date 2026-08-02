package com.drones.vision.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuration for device discovery ({@code vision.discovery.*}), mirroring {@link
 * VisionCvProperties}'s record-plus-{@code @DefaultValue} idiom, extended by
 * docs/LAYERING-REFACTOR-PLAN.md §2.2 (wave F4) with {@code adapter-discovery}'s mDNS scan-timing
 * budget and V4L2 filesystem bases.
 *
 * <p>{@link #mavlinkPort()} is the single source of truth for the UDP port a MAVLink heartbeat scan
 * listens on, shared by {@code wiring.DiscoveryWiring#mavlinkHeartbeatScanner} (which wires {@code
 * MavlinkHeartbeatScanner} against it) and {@code vision-api}'s {@code SystemNetworkController}
 * (which reports it as {@code mavlinkPort} in {@code GET /api/system/network}'s response, via a
 * plain {@code int} bean — see {@code wiring.DiscoveryWiring#mavlinkPort} — since {@code vision-api}
 * may not depend on {@code vision-app} to read this record directly).
 *
 * <p>{@link #mdns()} maps onto {@code com.drones.vision.adapter.discovery.mdns.ScanBudget}, threaded
 * into {@code MdnsScanner}'s constructor; {@link #v4l2()}'s {@code devBase}/{@code sysBase} map onto
 * {@code com.drones.vision.adapter.discovery.v4l2.V4l2Scanner}'s existing {@code (Path, Path)}
 * constructor.
 *
 * <p><b>{@code vision.discovery.enabled}</b> (whether {@code wiring.DiscoveryWiring}'s four scanner
 * beans are registered at all) deliberately stays outside this record, read directly via {@code
 * @ConditionalOnProperty} on each scanner {@code @Bean} method.
 *
 * @param mavlinkPort the UDP port {@code MavlinkHeartbeatScanner} listens on for MAVLink
 *                    heartbeats; must be a valid port number; default {@value #DEFAULT_MAVLINK_PORT}
 * @param mdns        {@code MdnsScanner}'s scan-timing budget; defaulted as a whole when absent
 * @param v4l2        {@code V4l2Scanner}'s filesystem bases; defaulted as a whole when absent
 */
@ConfigurationProperties(prefix = "vision.discovery")
public record VisionDiscoveryProperties(
        @DefaultValue(VisionDiscoveryProperties.DEFAULT_MAVLINK_PORT) int mavlinkPort,
        Mdns mdns,
        V4l2 v4l2) {

    static final String DEFAULT_MAVLINK_PORT = "14550";
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65_535;

    @ConstructorBinding
    public VisionDiscoveryProperties {
        if (mavlinkPort < MIN_PORT || mavlinkPort > MAX_PORT) {
            throw new IllegalArgumentException(
                    "vision.discovery.mavlink-port must be a valid UDP port (" + MIN_PORT + "-" + MAX_PORT
                            + "), was " + mavlinkPort);
        }
        if (mdns == null) {
            mdns = new Mdns(Mdns.DEFAULT_JOIN_GRACE_DURATION, Mdns.DEFAULT_SAFETY_MARGIN_DURATION,
                    Mdns.DEFAULT_MIN_LIST_WINDOW_DURATION);
        }
        if (v4l2 == null) {
            v4l2 = new V4l2(V4l2.DEFAULT_DEV_BASE_STRING, V4l2.DEFAULT_SYS_BASE_STRING);
        }
    }

    /**
     * Convenience constructor covering just the original {@code vision.discovery.mavlink-port}
     * field (predating wave F4's {@code mdns}/{@code v4l2} extension) — both nested records default
     * exactly as they would from an absent binding.
     */
    public VisionDiscoveryProperties(int mavlinkPort) {
        this(mavlinkPort, null, null);
    }

    /**
     * @param joinGrace     cushion reserved so a mDNS browse thread join has a realistic chance of
     *                      observing completion; default 150ms
     * @param safetyMargin  further cushion absorbing scheduling/JIT jitter; default 50ms
     * @param minListWindow floor for the mDNS {@code list()} window itself; default 50ms
     */
    public record Mdns(@DefaultValue("150ms") Duration joinGrace,
                        @DefaultValue("50ms") Duration safetyMargin,
                        @DefaultValue("50ms") Duration minListWindow) {
        static final Duration DEFAULT_JOIN_GRACE_DURATION = Duration.ofMillis(150);
        static final Duration DEFAULT_SAFETY_MARGIN_DURATION = Duration.ofMillis(50);
        static final Duration DEFAULT_MIN_LIST_WINDOW_DURATION = Duration.ofMillis(50);
    }

    /**
     * @param devBase filesystem root {@code V4l2Scanner} enumerates {@code videoN} nodes under;
     *                default {@value #DEFAULT_DEV_BASE_STRING}
     * @param sysBase filesystem root {@code V4l2Scanner} reads friendly device names under;
     *                default {@value #DEFAULT_SYS_BASE_STRING}
     */
    public record V4l2(@DefaultValue(V4l2.DEFAULT_DEV_BASE_STRING) String devBase,
                        @DefaultValue(V4l2.DEFAULT_SYS_BASE_STRING) String sysBase) {
        static final String DEFAULT_DEV_BASE_STRING = "/dev";
        static final String DEFAULT_SYS_BASE_STRING = "/sys";
    }
}

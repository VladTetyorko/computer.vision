package com.drones.vision.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuration for device discovery ({@code vision.discovery.*}), mirroring {@link
 * VisionCvProperties}'s record-plus-{@code @DefaultValue} idiom, extended by
 * docs/plans/active/LAYERING-REFACTOR-PLAN.md §2.2 (wave F4) with {@code adapter-discovery}'s mDNS scan-timing
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
 * <p>{@link #lobby()}/{@link #inbox()} (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §11,
 * Z2c) configure {@code com.drones.vision.app.discovery.DiscoveryInboxRunner}: {@link
 * Inbox#enabled()} gates whether that runner bean is registered at all (read via {@code
 * @ConditionalOnProperty} on its {@code @Bean} method, the same split {@link #mavlinkPort()}'s own
 * javadoc describes for {@code vision.discovery.enabled}); {@link Lobby#enabled()} gates one step
 * <em>inside</em> that runner's own sweep — whether it re-asserts the standing MAVLink lobby hold
 * this wave's cycle — checked in-line, not via a second {@code @Bean}, since holding the lobby is
 * not a bean of its own to conditionally register. Both default {@code true}: this feature's whole
 * point is discovery that works with zero configuration, so shipping it off by default would defeat
 * the plan it implements (see that document's own P2).
 *
 * <p>{@link #mediamtx()} (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §11 "Z3 amendment
 * (2026-08-31) — poll, not hook") configures {@code MediamtxPathScanner}, one of the {@link
 * #mediamtx()}-registered {@code DeviceDiscoveryPort} beans {@code wiring.DiscoveryWiring} wires.
 * Deliberately holds only {@link Mediamtx#enabled()} and {@link Mediamtx#pathPrefix()} — the
 * scanner's mediamtx Control API/RTSP reachability comes from {@code
 * VisionPublishProperties.Mediamtx#apiBase()}/{@code #rtspBase()} instead (the SAME properties
 * {@code MediamtxStreamPublisher} already uses to reach this exact mediamtx instance), not a
 * parallel pair here that could silently drift out of sync with it.
 *
 * @param mavlinkPort the UDP port {@code MavlinkHeartbeatScanner} listens on for MAVLink
 *                    heartbeats; must be a valid port number; default {@value #DEFAULT_MAVLINK_PORT}
 * @param mdns        {@code MdnsScanner}'s scan-timing budget; defaulted as a whole when absent
 * @param v4l2        {@code V4l2Scanner}'s filesystem bases; defaulted as a whole when absent
 * @param lobby       whether {@code DiscoveryInboxRunner} re-asserts the standing MAVLink lobby
 *                    hold each sweep; defaulted as a whole when absent
 * @param inbox       {@code DiscoveryInboxRunner}'s own enable flag and sweep timing; defaulted as
 *                    a whole when absent
 * @param mediamtx    {@code MediamtxPathScanner}'s enable flag and ingest path-name prefix;
 *                    defaulted as a whole when absent
 * @param live        whether {@code DiscoveryInboxService} is decorated to publish {@code
 *                    discovery} live-update deltas (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md
 *                    &sect;3.2 C4); defaulted as a whole when absent
 */
@ConfigurationProperties(prefix = "vision.discovery")
public record VisionDiscoveryProperties(
        @DefaultValue(VisionDiscoveryProperties.DEFAULT_MAVLINK_PORT) int mavlinkPort,
        Mdns mdns,
        V4l2 v4l2,
        Lobby lobby,
        Inbox inbox,
        Mediamtx mediamtx,
        Live live) {

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
        if (lobby == null) {
            lobby = new Lobby(Lobby.DEFAULT_ENABLED);
        }
        if (inbox == null) {
            inbox = new Inbox(Inbox.DEFAULT_ENABLED, Inbox.DEFAULT_SWEEP_SECONDS, Inbox.DEFAULT_SCAN_TIMEOUT_SECONDS);
        }
        if (mediamtx == null) {
            mediamtx = new Mediamtx(Mediamtx.DEFAULT_ENABLED, Mediamtx.DEFAULT_PATH_PREFIX);
        }
        if (live == null) {
            live = new Live(Live.DEFAULT_ENABLED);
        }
    }

    /**
     * Convenience constructor covering just the original {@code vision.discovery.mavlink-port}
     * field (predating wave F4's {@code mdns}/{@code v4l2} extension, Z2c's {@code lobby}/{@code
     * inbox} one, Z3's {@code mediamtx} one, and C4's {@code live} one) — every nested record
     * defaults exactly as it would from an absent binding.
     */
    public VisionDiscoveryProperties(int mavlinkPort) {
        this(mavlinkPort, null, null, null, null, null, null);
    }

    /**
     * @param enabled whether {@code DiscoveryInboxRunner} re-asserts the standing MAVLink lobby
     *                hold each sweep; default {@code true}
     */
    public record Lobby(@DefaultValue("true") boolean enabled) {
        static final boolean DEFAULT_ENABLED = true;
    }

    /**
     * @param enabled            whether {@code DiscoveryInboxRunner} is registered at all; default
     *                           {@code true}
     * @param sweepSeconds       how often the sweep runs; must be positive; default
     *                           {@value #DEFAULT_SWEEP_SECONDS}
     * @param scanTimeoutSeconds how long each sweep's {@code DiscoveryService.scan} call may take;
     *                           must be positive; default {@value #DEFAULT_SCAN_TIMEOUT_SECONDS}
     */
    public record Inbox(@DefaultValue("true") boolean enabled,
                         @DefaultValue("" + Inbox.DEFAULT_SWEEP_SECONDS) int sweepSeconds,
                         @DefaultValue("" + Inbox.DEFAULT_SCAN_TIMEOUT_SECONDS) int scanTimeoutSeconds) {
        static final boolean DEFAULT_ENABLED = true;
        static final int DEFAULT_SWEEP_SECONDS = 30;
        static final int DEFAULT_SCAN_TIMEOUT_SECONDS = 5;

        public Inbox {
            if (sweepSeconds <= 0) {
                throw new IllegalArgumentException(
                        "vision.discovery.inbox.sweep-seconds must be positive: " + sweepSeconds);
            }
            if (scanTimeoutSeconds <= 0) {
                throw new IllegalArgumentException(
                        "vision.discovery.inbox.scan-timeout-seconds must be positive: " + scanTimeoutSeconds);
            }
        }
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

    /**
     * @param enabled    whether {@code wiring.DiscoveryWiring} registers {@code MediamtxPathScanner}
     *                   (ANDed with the outer {@code vision.discovery.enabled} on the {@code @Bean}
     *                   method, the same two-flag pattern {@link Inbox#enabled()} follows against
     *                   its own outer flag); default {@code true}
     * @param pathPrefix mediamtx path-name prefix a device push must live under to be reported as a
     *                   candidate — the {@code ingest/} convention documented in {@code
     *                   mediamtx.yml}; paths outside this prefix are vision's own published streams
     *                   (MEDIA-SOT) and are never reported. Default {@value #DEFAULT_PATH_PREFIX}
     */
    public record Mediamtx(@DefaultValue("true") boolean enabled,
                            @DefaultValue(Mediamtx.DEFAULT_PATH_PREFIX) String pathPrefix) {
        static final boolean DEFAULT_ENABLED = true;
        static final String DEFAULT_PATH_PREFIX = "ingest/";

        public Mediamtx {
            if (pathPrefix == null || pathPrefix.isBlank()) {
                throw new IllegalArgumentException("vision.discovery.mediamtx.path-prefix must not be blank");
            }
        }
    }

    /**
     * @param enabled whether {@code DiscoveryInboxWiringConfiguration} decorates {@code
     *                DiscoveryInboxService} with a live-update-publishing wrapper; default {@code
     *                true} (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md &sect;3.2 C4, D8: ships on)
     */
    public record Live(@DefaultValue("true") boolean enabled) {
        static final boolean DEFAULT_ENABLED = true;
    }
}

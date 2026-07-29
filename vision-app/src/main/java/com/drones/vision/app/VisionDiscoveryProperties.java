package com.drones.vision.app;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for device discovery ({@code vision.discovery.*}), mirroring {@link
 * VisionCvProperties}'s record-plus-{@code @DefaultValue} idiom.
 *
 * <p>Today this carries exactly one value: {@link #mavlinkPort()}, the UDP port a MAVLink
 * heartbeat scan listens on. It is the single source of truth for that port, shared by {@link
 * DiscoveryWiringConfiguration#mavlinkHeartbeatScanner} (which wires {@code
 * MavlinkHeartbeatScanner} against it) and {@code vision-api}'s {@code SystemNetworkController}
 * (which reports it as {@code mavlinkPort} in {@code GET /api/system/network}'s response, via a
 * plain {@code int} bean this class's owner exposes — see {@link
 * DiscoveryWiringConfiguration#mavlinkPort} — since {@code vision-api} may not depend on {@code
 * vision-app} to read this record directly). Extracting one shared property rather than letting
 * each side hardcode its own {@value #DEFAULT_MAVLINK_PORT} is exactly what
 * docs/DRONE-INFRA-PLAN.md I-g's wave A asks for: the scanner and the onboarding wizard's
 * generated snippets must never be able to disagree about which port to use.
 *
 * <p><b>{@code vision.discovery.enabled}</b> (whether {@link DiscoveryWiringConfiguration}'s four
 * scanner beans are registered at all) deliberately stays outside this record, read directly via
 * {@code @ConditionalOnProperty} on each scanner {@code @Bean} method — gating bean
 * *registration* is evaluated before any {@code @ConfigurationProperties} bean is even bound, a
 * fundamentally different mechanism from a bound property value, so folding it in here would
 * only add an unused field alongside the real {@code @ConditionalOnProperty} check that still has
 * to stay.
 *
 * @param mavlinkPort the UDP port {@code MavlinkHeartbeatScanner} listens on for MAVLink
 *                    heartbeats — the well-known GCS port every telemetry radio/SITL pushes to by
 *                    default; must be a valid port number; default {@value #DEFAULT_MAVLINK_PORT}
 */
@ConfigurationProperties(prefix = "vision.discovery")
public record VisionDiscoveryProperties(
        @DefaultValue(VisionDiscoveryProperties.DEFAULT_MAVLINK_PORT) int mavlinkPort) {

    static final String DEFAULT_MAVLINK_PORT = "14550";
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65_535;

    public VisionDiscoveryProperties {
        if (mavlinkPort < MIN_PORT || mavlinkPort > MAX_PORT) {
            throw new IllegalArgumentException(
                    "vision.discovery.mavlink-port must be a valid UDP port (" + MIN_PORT + "-" + MAX_PORT
                            + "), was " + mavlinkPort);
        }
    }
}

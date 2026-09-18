package com.drones.vision.app.config.wiring;

import com.drones.mavlink.transport.LinkRegistry;
import com.drones.vision.adapter.mavlink.MavlinkTelemetrySource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@code drone-link/carrier-udp} and {@code drone-link/carrier-serial} onto the shared
 * MAVLink lobby's {@link LinkRegistry} (LINK-PAIRING-PLAN.md §3.2/§7) — sibling to {@link
 * TelemetryWiring}, which builds {@link MavlinkTelemetrySource} itself. This class never
 * constructs a gateway; it only reaches the {@link LinkRegistry} {@link
 * MavlinkTelemetrySource#linkRegistry(int)} exposes for the well-known lobby bind address, using
 * {@link DiscoveryWiringConfiguration#mavlinkPort} — the existing single-source-of-truth {@code
 * int} bean {@code MavlinkHeartbeatScanner} and {@code vision-api}'s {@code
 * SystemNetworkController} already share, rather than a third independent read of {@code
 * vision.discovery.mavlink-port}.
 *
 * <h2>No {@code @Import} needed</h2>
 * {@code UdpCarrierConfiguration} (drone-link/carrier-udp) and {@code SerialCarrierConfiguration}
 * (drone-link/carrier-serial) are themselves {@code @Configuration} classes, under {@code
 * com.drones.vision.adapter.carrierudp}/{@code carrierserial} — sub-packages of {@code
 * com.drones.vision}, the base package {@code VisionApplication}'s {@code @SpringBootApplication}
 * component-scans. Spring Boot auto-detects both the instant vision-app depends on those two
 * modules (see {@code pom.xml}), exactly like {@code adapter-persistence}'s repositories are
 * auto-detected today from a different module on the same classpath — no manual {@code @Import}
 * here. This class supplies the one collaborator neither of those two configurations can construct
 * itself: a concrete {@link LinkRegistry} (see each one's own "Depends on mavlink-core (+Spring)
 * only" javadoc section for why that must arrive as an injected bean rather than a direct
 * dependency on {@code drone-link/mavlink}).
 */
@Configuration
public class CarrierWiring {

    /**
     * @param mavlinkPort {@link DiscoveryWiringConfiguration#mavlinkPort}, reused rather than
     *                    re-reading {@code vision.discovery.mavlink-port} a third time
     */
    @Bean
    public LinkRegistry lobbyLinkRegistry(MavlinkTelemetrySource mavlinkTelemetrySource, int mavlinkPort) {
        return mavlinkTelemetrySource.linkRegistry(mavlinkPort);
    }
}

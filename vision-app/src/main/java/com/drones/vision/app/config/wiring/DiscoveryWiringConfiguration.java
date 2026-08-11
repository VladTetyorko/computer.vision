package com.drones.vision.app.config.wiring;

import com.drones.vision.adapter.discovery.mdns.MdnsScanner;
import com.drones.vision.adapter.discovery.mdns.ScanBudget;
import com.drones.vision.adapter.discovery.onvif.OnvifWsDiscoveryScanner;
import com.drones.vision.adapter.discovery.v4l2.V4l2Scanner;
import com.drones.vision.adapter.mavlink.MavlinkHeartbeatScanner;
import com.drones.vision.adapter.mavlink.MavlinkSettings;
import com.drones.vision.adapter.mavlink.MavlinkTelemetrySource;
import com.drones.vision.app.config.properties.VisionApplicationProperties;
import com.drones.vision.app.config.properties.VisionDiscoveryProperties;
import com.drones.vision.app.config.properties.VisionMavlinkProperties;
import com.drones.vision.application.discovery.DefaultDiscoveryService;
import com.drones.vision.application.discovery.DiscoveryService;
import com.drones.vision.warehouse.domain.port.DeviceDiscoveryPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Wires {@code adapter-discovery}'s scanners and the {@link
 * DiscoveryService} implementation, per docs/plans/done/DISCOVERY-PLAN.md Task D4.
 *
 * <p>The four scanner beans ({@link OnvifWsDiscoveryScanner}, {@link
 * MdnsScanner}, {@link V4l2Scanner}, {@link MavlinkHeartbeatScanner}) are
 * gated by {@code vision.discovery.enabled} (default {@code true}): each
 * opens a real socket/JmDNS instance/filesystem walk when invoked, so
 * setting the property to {@code false} keeps the context clean in
 * restricted environments (e.g. no multicast, no {@code /dev}) without
 * touching the discovery feature's other wiring.
 *
 * <p>{@link #mavlinkHeartbeatScanner} (docs/plans/active/DRONE-INFRA-PLAN.md I-b) lives in
 * {@code adapter-mavlink}, not {@code adapter-discovery} like the other
 * three — adapters never depend on each other (ArchUnit-enforced), and this
 * scanner needs to see {@link MavlinkTelemetrySource} directly so it can
 * borrow that bean's already-running {@code MavlinkSocketHub} instead of
 * failing to bind a port the gateway already owns (see the scanner's own
 * javadoc for the full hub-borrow/self-bind split). It is wired with the
 * same {@link MavlinkTelemetrySource} bean {@link WiringConfiguration}
 * registers for real telemetry ingest, and {@link
 * VisionDiscoveryProperties#mavlinkPort()} (docs/plans/active/DRONE-INFRA-PLAN.md I-g wave
 * A — previously a hardcoded constant, since none of this class's other
 * three scanners had a per-scanner property either; promoted to a real,
 * shared {@code vision.discovery.mavlink-port} property so the scanner and
 * {@code vision-api}'s new {@code GET /api/system/network} endpoint
 * ({@code SystemNetworkController}) — see {@link #mavlinkPort} below — can
 * never disagree about which port a heartbeat scan actually listens on).
 *
 * <p>{@link #discoveryService(List)} is <b>always</b> registered, regardless
 * of {@code vision.discovery.enabled}: {@link
 * com.drones.vision.api.DiscoveryController} depends unconditionally on
 * {@link DiscoveryService}, so the bean must exist for the context to
 * start at all. {@code DiscoveryService} tolerates an empty {@code
 * List<DeviceDiscoveryPort>} by construction — see its javadoc and {@link
 * DiscoveryService#scan}: with no ports
 * registered, {@code DiscoveryScanSpec.methods()} empty ("all methods") resolves
 * against an empty port registry, so the loop that fans out work simply has
 * nothing to iterate and {@code scan(...)} returns an empty {@code
 * DiscoveryScanResult} immediately rather than throwing — {@code
 * IllegalArgumentException} is only thrown for a request that explicitly
 * names an unknown method. So with scanning disabled, {@code POST
 * /api/discovery/scan} degrades gracefully to {@code
 * {"devices":[],"failedMethods":[]}} instead of failing the request or the
 * whole application context.
 */
@Configuration
@EnableConfigurationProperties({VisionDiscoveryProperties.class, VisionMavlinkProperties.class})
public class DiscoveryWiringConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "vision.discovery", name = "enabled", havingValue = "true", matchIfMissing = true)
    public OnvifWsDiscoveryScanner onvifWsDiscoveryScanner() {
        return new OnvifWsDiscoveryScanner();
    }

    /**
     * {@code scanBudget} maps {@link VisionDiscoveryProperties.Mdns} onto {@code
     * adapter-discovery}'s framework-free {@code ScanBudget} (docs/plans/active/LAYERING-REFACTOR-PLAN.md wave
     * F4) — the timeout-budget cushions carved out of a caller-requested scan window.
     */
    @Bean
    @ConditionalOnProperty(prefix = "vision.discovery", name = "enabled", havingValue = "true", matchIfMissing = true)
    public MdnsScanner mdnsScanner(VisionDiscoveryProperties properties) {
        VisionDiscoveryProperties.Mdns mdns = properties.mdns();
        return new MdnsScanner(new ScanBudget(mdns.joinGrace(), mdns.safetyMargin(), mdns.minListWindow()));
    }

    /**
     * {@code devBase}/{@code sysBase} map onto {@code V4l2Scanner}'s existing {@code (Path, Path)}
     * constructor (docs/plans/active/LAYERING-REFACTOR-PLAN.md wave F4) — only two tunables, no dedicated
     * settings record needed (§1.3 rule 4).
     */
    @Bean
    @ConditionalOnProperty(prefix = "vision.discovery", name = "enabled", havingValue = "true", matchIfMissing = true)
    public V4l2Scanner v4l2Scanner(VisionDiscoveryProperties properties) {
        VisionDiscoveryProperties.V4l2 v4l2 = properties.v4l2();
        return new V4l2Scanner(Path.of(v4l2.devBase()), Path.of(v4l2.sysBase()));
    }

    /**
     * docs/plans/active/DRONE-INFRA-PLAN.md I-b: plug-and-fly MAVLink heartbeat discovery, sharing {@code
     * mavlinkTelemetrySource}'s already-running {@code MavlinkSocketHub} whenever a real telemetry
     * device already has the port open (see class javadoc). {@code scan} maps {@link
     * VisionMavlinkProperties#scan()} onto {@code MavlinkSettings.Scan} (docs/plans/active/LAYERING-REFACTOR-PLAN.md
     * wave F2) — the same poll/self-bind-timeout budget {@code TelemetryWiring} builds a full {@code
     * MavlinkSettings} from, but this scanner needs only that one slice.
     */
    @Bean
    @ConditionalOnProperty(prefix = "vision.discovery", name = "enabled", havingValue = "true", matchIfMissing = true)
    public MavlinkHeartbeatScanner mavlinkHeartbeatScanner(MavlinkTelemetrySource mavlinkTelemetrySource,
                                                            VisionDiscoveryProperties properties,
                                                            VisionMavlinkProperties mavlinkProperties) {
        VisionMavlinkProperties.Scan scan = mavlinkProperties.scan();
        return new MavlinkHeartbeatScanner(mavlinkTelemetrySource, properties.mavlinkPort(),
                new MavlinkSettings.Scan(scan.activeHubPollCount(), scan.activeHubMinPollInterval(),
                        scan.selfBindMinReadTimeout(), scan.selfBindMaxReadTimeout()));
    }

    @Bean
    public DiscoveryService discoveryService(List<DeviceDiscoveryPort> discoveryPorts,
                                              VisionApplicationProperties applicationProperties) {
        return new DefaultDiscoveryService(discoveryPorts,
                Duration.ofMillis(applicationProperties.discoveryGraceMs()));
    }

    /**
     * The raw MAVLink heartbeat port value {@code vision-api}'s {@code SystemNetworkController}
     * (component-scanned from {@code com.drones.vision.api}) needs for {@code GET
     * /api/system/network}'s {@code mavlinkPort} field (docs/plans/active/DRONE-INFRA-PLAN.md I-g).
     *
     * <p>Supplied as a plain {@code int} bean — the same "hand the controller its one raw
     * collaborator as a bean, rather than constructing the controller here" pattern {@code
     * PublishWiring#hlsProxyUpstreamBase} already establishes for {@link
     * com.drones.vision.api.HlsProxyController}'s {@code URI} — because {@code vision-api} may
     * not depend on {@code vision-app} (the dependency rule runs the other way: {@code vision-app}
     * depends on {@code vision-api}, ArchUnit-enforced), so {@code SystemNetworkController} cannot
     * read {@link VisionDiscoveryProperties} itself. Both this method and {@link
     * #mavlinkHeartbeatScanner} take the exact same singleton {@link VisionDiscoveryProperties}
     * bean instance (Spring's normal singleton-bean sharing), so reading {@link
     * VisionDiscoveryProperties#mavlinkPort()} in both places — rather than duplicating the port as
     * a separate literal here — keeps this one property the single source of truth both consumers
     * read from, per I-g wave A's own goal.
     */
    @Bean
    public int mavlinkPort(VisionDiscoveryProperties properties) {
        return properties.mavlinkPort();
    }
}

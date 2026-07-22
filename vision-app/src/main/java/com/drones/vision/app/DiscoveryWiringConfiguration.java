package com.drones.vision.app;

import com.drones.vision.adapter.discovery.mdns.MdnsScanner;
import com.drones.vision.adapter.discovery.onvif.OnvifWsDiscoveryScanner;
import com.drones.vision.adapter.discovery.v4l2.V4l2Scanner;
import com.drones.vision.application.DefaultDiscoveryService;
import com.drones.vision.application.DiscoveryService;
import com.drones.vision.domain.port.out.DeviceDiscoveryPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Wires {@code adapter-discovery}'s scanners and the {@link
 * DiscoveryService} implementation, per docs/DISCOVERY-PLAN.md Task D4.
 *
 * <p>The three scanner beans ({@link OnvifWsDiscoveryScanner}, {@link
 * MdnsScanner}, {@link V4l2Scanner}) are gated by {@code
 * vision.discovery.enabled} (default {@code true}): each opens a real
 * socket/JmDNS instance/filesystem walk when invoked, so setting the
 * property to {@code false} keeps the context clean in restricted
 * environments (e.g. no multicast, no {@code /dev}) without touching the
 * discovery feature's other wiring.
 *
 * <p>{@link #discoveryService(List)} is <b>always</b> registered, regardless
 * of {@code vision.discovery.enabled}: {@link
 * com.drones.vision.api.DiscoveryController} depends unconditionally on
 * {@link DiscoveryService}, so the bean must exist for the context to
 * start at all. {@code DiscoveryService} tolerates an empty {@code
 * List<DeviceDiscoveryPort>} by construction — see its javadoc and {@link
 * com.drones.vision.application.DiscoveryService#scan}: with no ports
 * registered, {@code ScanRequest.methods()} empty ("all methods") resolves
 * against an empty port registry, so the loop that fans out work simply has
 * nothing to iterate and {@code scan(...)} returns an empty {@code
 * ScanResult} immediately rather than throwing — {@code
 * IllegalArgumentException} is only thrown for a request that explicitly
 * names an unknown method. So with scanning disabled, {@code POST
 * /api/discovery/scan} degrades gracefully to {@code
 * {"devices":[],"failedMethods":[]}} instead of failing the request or the
 * whole application context.
 */
@Configuration
public class DiscoveryWiringConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "vision.discovery", name = "enabled", havingValue = "true", matchIfMissing = true)
    public OnvifWsDiscoveryScanner onvifWsDiscoveryScanner() {
        return new OnvifWsDiscoveryScanner();
    }

    @Bean
    @ConditionalOnProperty(prefix = "vision.discovery", name = "enabled", havingValue = "true", matchIfMissing = true)
    public MdnsScanner mdnsScanner() {
        return new MdnsScanner();
    }

    @Bean
    @ConditionalOnProperty(prefix = "vision.discovery", name = "enabled", havingValue = "true", matchIfMissing = true)
    public V4l2Scanner v4l2Scanner() {
        return new V4l2Scanner();
    }

    @Bean
    public DiscoveryService discoveryService(List<DeviceDiscoveryPort> discoveryPorts) {
        return new DefaultDiscoveryService(discoveryPorts);
    }
}

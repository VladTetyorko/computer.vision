package com.drones.vision.app.config.wiring;

import com.drones.vision.adapter.mavlink.MavlinkTelemetrySource;
import com.drones.vision.app.config.properties.VisionDiscoveryProperties;
import com.drones.vision.app.discovery.DiscoveryInboxRunner;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.discovery.DefaultDiscoveryInboxService;
import com.drones.vision.warehouse.application.discovery.DiscoveryInboxService;
import com.drones.vision.warehouse.application.discovery.DiscoveryService;
import com.drones.vision.warehouse.domain.port.DiscoveryCandidateRepositoryPort;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the discovery inbox (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md &sect;3 P2,
 * &sect;11, Z2c) — a separate {@code @Configuration} from {@code DiscoveryWiringConfiguration},
 * mirroring {@code UsageWiringConfiguration}'s own "keep a new feature's wiring in its own file"
 * precedent.
 *
 * <p>{@link #discoveryInboxService} composes {@link DiscoveryCandidateRepositoryPort}
 * (unconditional, {@link PersistenceWiringConfiguration}) and {@link AssetService} (unconditional,
 * {@link ApplicationServiceWiring}) — always registered, since {@code
 * com.drones.vision.api.controller.DiscoveryInboxController} depends on it unconditionally (the
 * inbox can still be read/dismissed/registered through by hand even with the sweep runner itself
 * disabled), the same "service bean always exists, only the runner is gated" split {@link
 * #discoveryInboxRunner} below follows for {@code UsageWiringConfiguration#usageIdleCloseRunner}'s
 * own precedent.
 *
 * <p>{@link #discoveryInboxRunner} is gated on {@link VisionDiscoveryProperties.Inbox#enabled()}
 * (default {@code true} — see that record's own javadoc for why this feature ships on by default).
 * {@code initMethod = "start"} arms the sweep loop as soon as this bean is constructed; {@code
 * destroyMethod = "close"} stops only this bean's own scheduler, mirroring {@code
 * UsageIdleCloseRunner}'s/{@code TrackProjectionRunner}'s lifecycle idiom.
 */
@Configuration
@EnableConfigurationProperties(VisionDiscoveryProperties.class)
public class DiscoveryInboxWiringConfiguration {

    @Bean
    public DiscoveryInboxService discoveryInboxService(DiscoveryCandidateRepositoryPort candidateRepositoryPort,
                                                         AssetService assetService) {
        return new DefaultDiscoveryInboxService(candidateRepositoryPort, assetService);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(prefix = "vision.discovery.inbox", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public DiscoveryInboxRunner discoveryInboxRunner(DiscoveryInboxService discoveryInboxService,
                                                       DiscoveryService discoveryService,
                                                       MavlinkTelemetrySource mavlinkTelemetrySource,
                                                       VisionDiscoveryProperties properties) {
        return new DiscoveryInboxRunner(discoveryInboxService, discoveryService, mavlinkTelemetrySource, properties);
    }
}

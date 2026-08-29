package com.drones.vision.app.config.wiring;

import com.drones.vision.app.config.properties.VisionUsageProperties;
import com.drones.vision.app.usage.UsageIdleCloseRunner;
import com.drones.vision.warehouse.application.usage.DefaultUsageIdleCloseService;
import com.drones.vision.warehouse.application.usage.IdleUsageCloseSettings;
import com.drones.vision.warehouse.application.usage.UsageIdleCloseService;
import com.drones.vision.warehouse.application.usage.UsageSessionService;
import com.drones.vision.warehouse.domain.port.AssetLiveStatePort;
import com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires U1's idle-usage-close sweep (docs/plans/active/OPERATOR-UX-5-PLAN.md finding U1, wave W1) — a
 * separate {@code @Configuration} from {@code ApplicationServiceWiring}, mirroring {@code
 * FixedCameraGeoWiringConfiguration}'s own "keep a new feature's wiring in its own file" precedent.
 *
 * <p>{@link #usageIdleCloseService} composes {@link AssetUsageRepositoryPort} (unconditional, {@code
 * PersistenceWiringConfiguration}) and {@link AssetLiveStatePort}/{@link UsageSessionService} (both
 * already unconditional beans on {@code ApplicationServiceWiring}) — no new bean cycle: this service
 * is a pure downstream consumer of all three, nothing upstream depends back on it.
 *
 * <p>Both beans are unconditional — see {@link VisionUsageProperties}'s own javadoc for why this
 * fix carries no enable flag.
 */
@Configuration
@EnableConfigurationProperties(VisionUsageProperties.class)
public class UsageWiringConfiguration {

    @Bean
    public UsageIdleCloseService usageIdleCloseService(AssetUsageRepositoryPort assetUsageRepositoryPort,
                                                         AssetLiveStatePort assetLiveStatePort,
                                                         UsageSessionService usageSessionService,
                                                         VisionUsageProperties properties) {
        return new DefaultUsageIdleCloseService(assetUsageRepositoryPort, assetLiveStatePort, usageSessionService,
                new IdleUsageCloseSettings(properties.idleClose()));
    }

    /**
     * {@code initMethod = "start"} arms the sweep loop as soon as this bean is constructed; {@code
     * destroyMethod = "close"} stops only this bean's own scheduler, mirroring {@code
     * TrackProjectionRunner}'s/{@code CvChannelSupervisor}'s lifecycle idiom.
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    public UsageIdleCloseRunner usageIdleCloseRunner(UsageIdleCloseService usageIdleCloseService,
                                                       VisionUsageProperties properties) {
        return new UsageIdleCloseRunner(usageIdleCloseService, properties);
    }
}

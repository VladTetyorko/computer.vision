package com.drones.vision.app;

import com.drones.vision.api.controller.DiscoveryInboxController;
import com.drones.vision.app.config.properties.VisionDiscoveryProperties;
import com.drones.vision.app.discovery.DiscoveryInboxRunner;
import com.drones.vision.warehouse.application.discovery.DiscoveryInboxService;
import com.drones.vision.warehouse.domain.port.DiscoveryCandidateRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Context test for the <em>default</em> configuration (no {@code vision.discovery.inbox.*}/{@code
 * vision.discovery.lobby.*} overrides): asserts {@link
 * com.drones.vision.app.config.wiring.DiscoveryInboxWiringConfiguration} registers {@link
 * DiscoveryInboxService} and {@link DiscoveryInboxRunner} per both flags' default of {@code true}
 * (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md &sect;11, Z2c), and that {@link
 * DiscoveryCandidateRepositoryPort} (from {@link
 * com.drones.vision.app.config.wiring.PersistenceWiringConfiguration}) and {@link
 * DiscoveryInboxController} (component-scanned from {@code com.drones.vision.api}) both wire
 * cleanly against it.
 *
 * <p>{@code vision.publish.enabled=false} is set for the same determinism reasons as {@link
 * com.drones.vision.VisionApplicationTests}. See {@link DiscoveryInboxDisabledWiringTest} for the
 * {@code vision.discovery.inbox.enabled=false} counterpart.
 */
@SpringBootTest(properties = "vision.publish.enabled=false")
class DiscoveryInboxWiringTest {

    @Autowired
    private VisionDiscoveryProperties properties;

    @Autowired
    private DiscoveryCandidateRepositoryPort discoveryCandidateRepositoryPort;

    @Autowired
    private DiscoveryInboxService discoveryInboxService;

    @Autowired
    private DiscoveryInboxRunner discoveryInboxRunner;

    @Autowired
    private DiscoveryInboxController discoveryInboxController;

    @Test
    void lobbyAndInboxDefaultToEnabled() {
        assertTrue(properties.lobby().enabled());
        assertTrue(properties.inbox().enabled());
    }

    @Test
    void everyDiscoveryInboxBeanWiresCleanlyByDefault() {
        assertNotNull(discoveryCandidateRepositoryPort);
        assertNotNull(discoveryInboxService);
        assertNotNull(discoveryInboxRunner);
        assertNotNull(discoveryInboxController);
    }
}

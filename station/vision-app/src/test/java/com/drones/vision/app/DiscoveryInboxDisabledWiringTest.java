package com.drones.vision.app;

import com.drones.vision.app.discovery.DiscoveryInboxRunner;
import com.drones.vision.warehouse.application.discovery.DiscoveryInboxService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Context test for {@code vision.discovery.inbox.enabled=false} (docs/plans/active/
 * ZERO-CONFIG-ONBOARDING-CONTEXT.md &sect;11, Z2c): asserts the context still loads cleanly and
 * {@link DiscoveryInboxService} is still wired — required because {@code
 * com.drones.vision.api.controller.DiscoveryInboxController} depends on it unconditionally, so an
 * operator can still read/dismiss/register through the inbox by hand — but with no {@link
 * DiscoveryInboxRunner} bean registered, i.e. no background sweep.
 *
 * <p>{@code vision.publish.enabled=false} is set for the same determinism reasons as {@link
 * DiscoveryInboxWiringTest}.
 */
@SpringBootTest(properties = {"vision.discovery.inbox.enabled=false", "vision.publish.enabled=false"})
class DiscoveryInboxDisabledWiringTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private DiscoveryInboxService discoveryInboxService;

    @Test
    void discoveryInboxServiceIsStillWiredWithTheRunnerDisabled() {
        assertNotNull(discoveryInboxService);
    }

    @Test
    void noRunnerIsRegisteredWhenDisabled() {
        assertTrue(applicationContext.getBeansOfType(DiscoveryInboxRunner.class).isEmpty(),
                "expected no DiscoveryInboxRunner bean when vision.discovery.inbox.enabled=false");
    }
}

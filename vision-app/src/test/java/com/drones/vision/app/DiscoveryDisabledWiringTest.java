package com.drones.vision.app;

import com.drones.vision.warehouse.application.discovery.DiscoveryService;
import com.drones.vision.warehouse.application.discovery.DiscoveryScanSpec;
import com.drones.vision.warehouse.application.discovery.DiscoveryScanResult;
import com.drones.vision.warehouse.domain.port.DeviceDiscoveryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Context test for {@code vision.discovery.enabled=false}: asserts the
 * context still loads cleanly (no scanner requires network/filesystem
 * access at wiring time) but with zero {@link DeviceDiscoveryPort} beans
 * registered, and that {@link DiscoveryService} (backed by {@code
 * DiscoveryService}) is still wired -- required because {@code
 * DiscoveryController} depends on it unconditionally -- and degrades
 * gracefully with an empty port registry rather than failing.
 *
 * <p>Looks up {@link DeviceDiscoveryPort} beans via {@link
 * ApplicationContext#getBeansOfType} rather than {@code @Autowired List<>}:
 * unlike a {@code @Bean} factory-method parameter (which {@link
 * DiscoveryWiringConfiguration#discoveryService} relies on to tolerate zero
 * candidates), a plain {@code @Autowired} field/collection injection point
 * is {@code required} by default and throws {@code
 * NoSuchBeanDefinitionException} when no candidates exist, which would
 * defeat the point of this test.
 *
 * <p>{@code vision.publish.enabled=false} is set for the same determinism
 * reasons as {@link DiscoveryWiringTest}.
 */
@SpringBootTest(properties = {"vision.discovery.enabled=false", "vision.publish.enabled=false"})
class DiscoveryDisabledWiringTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private DiscoveryService discoveryService;

    @Test
    void noScannersAreRegisteredWhenDisabled() {
        assertTrue(applicationContext.getBeansOfType(DeviceDiscoveryPort.class).isEmpty(),
                "expected no DeviceDiscoveryPort beans when vision.discovery.enabled=false");
    }

    @Test
    void scanGracefullyReturnsEmptyResultWithNoRegisteredPorts() {
        DiscoveryScanResult result = discoveryService.scan(DiscoveryScanSpec.defaults());

        assertTrue(result.devices().isEmpty());
        assertTrue(result.failedMethods().isEmpty());
    }
}

package com.drones.vision.app;

import com.drones.vision.adapter.discovery.mediamtx.MediamtxPathScanner;
import com.drones.vision.warehouse.domain.port.DeviceDiscoveryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Context test for {@code vision.discovery.mediamtx.enabled=false} (docs/plans/active/
 * ZERO-CONFIG-ONBOARDING-CONTEXT.md &sect;11 "Z3 amendment"): asserts the context still loads
 * cleanly, no {@link MediamtxPathScanner} bean is registered, and the other four scanners are
 * unaffected -- the two-flag {@code @ConditionalOnProperty} on {@link
 * com.drones.vision.app.config.wiring.DiscoveryWiringConfiguration#mediamtxPathScanner} gates only
 * this one bean, not the whole {@code vision.discovery.enabled} feature.
 *
 * <p>See {@link DiscoveryWiringTest} for the default-enabled counterpart (all five methods
 * present) and {@link DiscoveryDisabledWiringTest} for {@code vision.discovery.enabled=false}
 * (every scanner, including this one, absent).
 */
@SpringBootTest(properties = {"vision.publish.enabled=false", "vision.discovery.mediamtx.enabled=false"})
class DiscoveryMediamtxWiringTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private List<DeviceDiscoveryPort> discoveryPorts;

    @Test
    void noMediamtxScannerBeanWhenDisabled() {
        assertTrue(applicationContext.getBeansOfType(MediamtxPathScanner.class).isEmpty(),
                "expected no MediamtxPathScanner bean when vision.discovery.mediamtx.enabled=false");
    }

    @Test
    void theOtherFourScannersAreStillRegistered() {
        Set<String> methods = discoveryPorts.stream().map(DeviceDiscoveryPort::method).collect(Collectors.toSet());

        assertTrue(methods.containsAll(Set.of("onvif", "mdns", "v4l2", "mavlink")));
        assertFalse(methods.contains("mediamtx"));
    }
}

package com.drones.vision.app;

import com.drones.vision.domain.port.out.DeviceDiscoveryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Context test for the <em>default</em> configuration (no {@code
 * vision.discovery.*} overrides): asserts {@link DiscoveryWiringConfiguration}
 * registers all three {@code adapter-discovery} scanners plus {@code
 * adapter-mavlink}'s {@code MavlinkHeartbeatScanner} (docs/DRONE-INFRA-PLAN.md
 * I-b) per {@code vision.discovery.enabled}'s default of {@code true}.
 *
 * <p>{@code vision.publish.enabled=false} is set for determinism, same as
 * {@link com.drones.vision.VisionApplicationTests} and {@link
 * SimStreamSmokeTest} -- this test only cares about the discovery wiring and
 * shouldn't depend on mediamtx being reachable to stay green.
 *
 * <p>See {@link DiscoveryDisabledWiringTest} for the {@code
 * vision.discovery.enabled=false} counterpart.
 */
@SpringBootTest(properties = "vision.publish.enabled=false")
class DiscoveryWiringTest {

    @Autowired
    private List<DeviceDiscoveryPort> discoveryPorts;

    @Test
    void allFourDiscoveryMethodsAreRegisteredByDefault() {
        Set<String> methods = discoveryPorts.stream().map(DeviceDiscoveryPort::method).collect(Collectors.toSet());

        assertEquals(Set.of("onvif", "mdns", "v4l2", "mavlink"), methods);
    }
}

package com.drones.vision.app;

import com.drones.vision.adapter.mavlink.MavlinkHeartbeatScanner;
import com.drones.vision.app.config.properties.VisionDiscoveryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Context test for a <em>non-default</em> {@code vision.discovery.mavlink-port}
 * (docs/plans/active/DRONE-INFRA-PLAN.md I-g wave A) — a cross-consistency proof {@link
 * DiscoveryWiringTest}'s default-value test can't fully make on its own: a bean equal to
 * {@code 14550} could still, in principle, be a coincidental hardcoded literal rather than one
 * genuinely read from the property. Overriding to a value ({@value #OVERRIDDEN_PORT}) nothing in
 * this codebase would ever hardcode rules that out.
 *
 * <p>Both {@link DiscoveryWiringConfiguration#mavlinkPort} and {@link
 * DiscoveryWiringConfiguration#mavlinkHeartbeatScanner} take the exact same singleton {@link
 * VisionDiscoveryProperties} bean instance as a constructor/{@code @Bean}-method argument (plain
 * Spring singleton-bean sharing — {@code @EnableConfigurationProperties} registers exactly one
 * bean of that type), so asserting the plain {@code int} bean {@code vision-api}'s {@code
 * SystemNetworkController} consumes equals the overridden property value, <em>and</em> that {@link
 * MavlinkHeartbeatScanner} still constructs cleanly from that same property source, is the
 * strongest proof achievable from {@code vision-app}/{@code vision-api} alone: {@link
 * MavlinkHeartbeatScanner} (adapter-mavlink, out of this task's file scope) exposes no getter for
 * its own configured port to assert against directly.
 */
@SpringBootTest(properties = {"vision.publish.enabled=false", "vision.discovery.mavlink-port=25000"})
class DiscoveryMavlinkPortOverrideWiringTest {

    private static final int OVERRIDDEN_PORT = 25_000;

    @Autowired
    private VisionDiscoveryProperties properties;

    @Autowired
    private int mavlinkPort;

    @Autowired
    private MavlinkHeartbeatScanner mavlinkHeartbeatScanner;

    @Test
    void theOverriddenPortReachesBothVisionDiscoveryPropertiesAndTheMavlinkPortBean() {
        assertEquals(OVERRIDDEN_PORT, properties.mavlinkPort());
        assertEquals(OVERRIDDEN_PORT, mavlinkPort);
    }

    @Test
    void theHeartbeatScannerStillWiresCleanlyAgainstTheOverriddenPort() {
        assertNotNull(mavlinkHeartbeatScanner);
    }
}

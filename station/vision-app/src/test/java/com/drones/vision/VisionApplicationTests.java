package com.drones.vision;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * {@code vision.publish.enabled=false} is set for determinism: this test
 * should stay green regardless of whether mediamtx is running (see {@code
 * com.drones.vision.app.PublishWiringTest} for the default-configuration
 * context test that does exercise the mediamtx-backed wiring).
 */
@SpringBootTest(properties = "vision.publish.enabled=false")
class VisionApplicationTests {

    @Test
    void contextLoads() {
    }

}

package com.drones.vision.app;

import com.drones.vision.adapter.cvgrpc.GrpcDetectionPort;
import com.drones.vision.domain.port.out.DetectionPort;
import com.drones.vision.domain.port.out.EventPublisherPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Context test for {@code vision.cv.enabled=true}: asserts {@link WiringConfiguration} resolves
 * {@link DetectionPort} to a {@link GrpcDetectionPort} built from {@code vision.cv.endpoint}, and
 * wraps {@link EventPublisherPort} in {@link DetectionSessionCleanupEventPublisher} so the gRPC
 * port's per-stream session gets cleaned up on {@code STREAM_STOPPED} — see {@link CvWiringTest}
 * for the default (disabled) counterpart.
 *
 * <p>The configured endpoint is never actually connected to by this test (a {@code
 * ManagedChannel} only opens a real connection lazily, on first use) — it only needs to parse
 * cleanly into a host/port for {@link VisionCvProperties#host()}/{@link
 * VisionCvProperties#port()}; the full round trip against a real (in-test) gRPC server is
 * covered by {@link CvDetectionE2ETest}. {@code vision.publish.enabled=false} for the same
 * determinism reasons as {@link AssetWiringTest}.
 */
@SpringBootTest(properties = {
        "vision.publish.enabled=false",
        "vision.cv.enabled=true",
        "vision.cv.endpoint=localhost:59321"
})
class CvEnabledWiringTest {

    @Autowired
    private DetectionPort detectionPort;

    @Autowired
    private EventPublisherPort eventPublisherPort;

    @Test
    void enabledConfigurationSelectsGrpcDetectionPort() {
        assertInstanceOf(GrpcDetectionPort.class, detectionPort);
    }

    @Test
    void enabledConfigurationWrapsEventPublisherWithSessionCleanupDecorator() {
        assertInstanceOf(DetectionSessionCleanupEventPublisher.class, eventPublisherPort);
    }
}

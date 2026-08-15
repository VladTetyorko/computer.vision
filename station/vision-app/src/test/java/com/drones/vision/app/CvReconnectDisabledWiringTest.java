package com.drones.vision.app;

import com.drones.vision.adapter.cvgrpc.CvChannelSupervisor;
import com.drones.vision.adapter.cvgrpc.GrpcDetectionPort;
import com.drones.vision.perception.domain.port.DetectionPort;
import io.grpc.ManagedChannel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Context test for {@code vision.cv.enabled=true} <strong>and</strong> {@code
 * vision.cv.reconnect.enabled=false} (docs/plans/active/CV-RECONNECT-PLAN.md §3.3/§5 item 3) — the
 * escape hatch: asserts {@link CvWiring#cvChannelSupervisor} is entirely absent (its {@code
 * @ConditionalOnExpression} ANDs {@code vision.cv.reconnect.enabled}), that {@link
 * CvWiring#detectionPort} still resolves to a real {@link GrpcDetectionPort} regardless (falling back
 * to the pre-R2 two-arg constructor, which has no gate), and that the shared {@link ManagedChannel}
 * is still built — the reconnect flag governs only the supervisor/gate, not whether CV is wired at
 * all. See {@link CvEnabledWiringTest} for the (default) {@code reconnect.enabled=true} counterpart.
 *
 * <p>{@code vision.publish.enabled=false}/{@code vision.live.enabled=false} for the same isolation
 * reasons as {@link CvEnabledWiringTest}.
 */
@SpringBootTest(properties = {
        "vision.publish.enabled=false",
        "vision.cv.enabled=true",
        "vision.cv.endpoint=localhost:59323",
        "vision.cv.reconnect.enabled=false",
        "vision.live.enabled=false"
})
class CvReconnectDisabledWiringTest {

    @Autowired
    private DetectionPort detectionPort;

    @Autowired
    private ManagedChannel cvGrpcChannel;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void reconnectDisabledStillSelectsGrpcDetectionPortViaTheTwoArgFallbackConstructor() {
        assertInstanceOf(GrpcDetectionPort.class, detectionPort);
    }

    @Test
    void reconnectDisabledBuildsNoCvChannelSupervisor() {
        assertTrue(applicationContext.getBeansOfType(CvChannelSupervisor.class).isEmpty());
    }

    @Test
    void reconnectDisabledStillBuildsTheSharedChannel() {
        assertInstanceOf(ManagedChannel.class, cvGrpcChannel);
    }
}

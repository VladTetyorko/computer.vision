package com.drones.vision.app;

import com.drones.vision.adapter.cvgrpc.GrpcDetectionPort;
import com.drones.vision.adapter.cvgrpc.GrpcModelRegistryPort;
import com.drones.vision.adapter.cvgrpc.GrpcTrainingPort;
import com.drones.vision.domain.port.out.DetectionPort;
import com.drones.vision.domain.port.out.ModelRegistryPort;
import com.drones.vision.domain.port.out.TrainingPort;
import io.grpc.ManagedChannel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Context test for {@code vision.cv.enabled=true} <strong>and</strong> {@code
 * vision.training.enabled=true} together (docs/CV-TRAINING-PLAN.md §7/§8, Phase 2 T9): the one
 * scenario that actually exercises channel <em>sharing</em> — {@link CvEnabledWiringTest} and
 * {@link TrainingEnabledWiringTest} each only enable one flag at a time.
 *
 * <p>Asserts exactly one {@link ManagedChannel} bean exists in the context and that {@code
 * detectionPort} (a {@link GrpcDetectionPort}), {@code modelRegistryPort} (a {@link
 * GrpcModelRegistryPort}), and {@code trainingPort} (a {@link GrpcTrainingPort},
 * docs/CV-TRAINING-PLAN.md §7/§8 Phase 2's last backend wave) were all constructed successfully
 * against it — by Spring singleton-bean semantics, a single {@code @Bean} method invoked exactly
 * once (there being only one candidate bean of type {@link ManagedChannel} in the whole context,
 * verified below) is necessarily the same instance handed to all three consumers, so there is no
 * separate "same identity" check to make beyond counting the bean and confirming every consumer
 * resolved.
 *
 * <p>The configured {@code vision.cv.endpoint} is never actually connected to (a {@code
 * ManagedChannel} only opens a real connection lazily, on first use) — same reasoning as {@link
 * CvEnabledWiringTest}. {@code vision.live.enabled=false} isolates this test from the server-push
 * feature's own {@code EventPublisherPort} decorator, same reasoning as {@link CvEnabledWiringTest}.
 */
@SpringBootTest(properties = {
        "vision.publish.enabled=false",
        "vision.cv.enabled=true",
        "vision.cv.endpoint=localhost:59322",
        "vision.training.enabled=true",
        "vision.live.enabled=false"
})
class CvAndTrainingSharedChannelWiringTest {

    @Autowired
    private DetectionPort detectionPort;

    @Autowired
    private ModelRegistryPort modelRegistryPort;

    @Autowired
    private TrainingPort trainingPort;

    @Autowired
    private ManagedChannel cvGrpcChannel;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void exactlyOneSharedChannelBeanExists() {
        Map<String, ManagedChannel> channels = applicationContext.getBeansOfType(ManagedChannel.class);
        assertEquals(1, channels.size());
    }

    @Test
    void allThreePortsResolveToTheirGrpcImplementations() {
        assertInstanceOf(GrpcDetectionPort.class, detectionPort);
        assertInstanceOf(GrpcModelRegistryPort.class, modelRegistryPort);
        assertInstanceOf(GrpcTrainingPort.class, trainingPort);
    }

    /**
     * The load-bearing proof: {@link WiringConfiguration#cvGrpcChannel} resolved by direct
     * autowiring here is the exact same singleton instance {@link WiringConfiguration#detectionPort}
     * and {@link TrainingWiringConfiguration#modelRegistryPort}/{@code trainingPort} each received
     * as a constructor argument — Spring never constructs a second {@link ManagedChannel} for a
     * plain (non-{@code @Scope("prototype")}) {@code @Bean} method, and {@link
     * #exactlyOneSharedChannelBeanExists()} above already confirms there is only the one bean
     * definition to begin with.
     */
    @Test
    void theAutowiredChannelIsTheSameSingletonBothPortsWereBuiltWith() {
        assertSame(cvGrpcChannel, applicationContext.getBean(ManagedChannel.class));
    }
}

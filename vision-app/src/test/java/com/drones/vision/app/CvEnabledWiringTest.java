package com.drones.vision.app;

import com.drones.vision.adapter.cvgrpc.GrpcDetectionPort;
import com.drones.vision.api.controller.ModelRegistryController;
import com.drones.vision.app.events.DetectionSessionCleanupEventPublisher;
import com.drones.vision.application.training.ModelRegistryService;
import com.drones.vision.perception.domain.port.DetectionPort;
import com.drones.vision.events.domain.port.EventPublisherPort;
import com.drones.vision.learning.domain.port.ModelRegistryPort;
import io.grpc.ManagedChannel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * determinism reasons as {@link AssetWiringTest}. {@code vision.live.enabled=false}
 * (docs/plans/done/REALTIME-PLAN.md §4) isolates this test from the server-push feature's own {@code
 * EventPublisherPort} decorator ({@link LiveUpdateEventPublisher}, which would otherwise wrap
 * {@link DetectionSessionCleanupEventPublisher} one layer further out by default) — see {@link
 * LiveWiringTest}/{@link LiveDisabledWiringTest} for that feature's own coverage.
 *
 * <p>{@code vision.cv.detect-width}/{@code vision.cv.jpeg-quality} (docs/plans/done/REMOTE-CV-PLAN.md P1
 * item 5) are set here to non-default values purely to prove Spring binds the kebab-case
 * property names onto {@link VisionCvProperties#detectWidth()}/{@link
 * VisionCvProperties#jpegQuality()} and the context still starts cleanly with them threaded into
 * {@code WiringConfiguration#detectionPort} — {@code GrpcDetectionPort} exposes no getter for
 * either (they only affect wire behavior, asserted directly in adapter-cv-grpc's own {@code
 * GrpcDetectionPortTest}), so a successful context load plus the {@code GrpcDetectionPort}
 * {@code instanceof} check below is the full extent of what this class can observe.
 *
 * <p>Also proves the detection-only half of the shared-channel wiring (docs/plans/done/CV-TRAINING-PLAN.md
 * §7/§8, Phase 2 T9): with {@code vision.training.enabled} left at its default {@code false},
 * {@link WiringConfiguration#cvGrpcChannel} is still built (its {@code @ConditionalOnExpression}
 * matches on {@code vision.cv.enabled} alone), but the model registry controller/service/port stay
 * entirely absent — a CV-only deployment doesn't accidentally light up the training endpoints. See
 * {@link TrainingEnabledWiringTest} for the training-only mirror and {@link
 * CvAndTrainingSharedChannelWiringTest} for the both-enabled case that actually proves the channel
 * is the <em>same instance</em> both ports consume.
 */
@SpringBootTest(properties = {
        "vision.publish.enabled=false",
        "vision.cv.enabled=true",
        "vision.cv.endpoint=localhost:59321",
        "vision.cv.detect-width=480",
        "vision.cv.jpeg-quality=0.6",
        "vision.live.enabled=false"
})
class CvEnabledWiringTest {

    @Autowired
    private DetectionPort detectionPort;

    @Autowired
    private EventPublisherPort eventPublisherPort;

    @Autowired
    private ManagedChannel cvGrpcChannel;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void enabledConfigurationSelectsGrpcDetectionPort() {
        assertInstanceOf(GrpcDetectionPort.class, detectionPort);
    }

    @Test
    void enabledConfigurationWrapsEventPublisherWithSessionCleanupDecorator() {
        assertInstanceOf(DetectionSessionCleanupEventPublisher.class, eventPublisherPort);
    }

    @Test
    void cvOnlyConfigurationStillBuildsTheSharedChannel() {
        assertInstanceOf(ManagedChannel.class, cvGrpcChannel);
    }

    @Test
    void cvOnlyConfigurationDoesNotWireTheModelRegistry() {
        assertTrue(applicationContext.getBeansOfType(ModelRegistryController.class).isEmpty());
        assertTrue(applicationContext.getBeansOfType(ModelRegistryService.class).isEmpty());
        assertTrue(applicationContext.getBeansOfType(ModelRegistryPort.class).isEmpty());
    }
}

package com.drones.vision.app;

import com.drones.vision.adapter.cvgrpc.CvChannelSupervisor;
import com.drones.vision.adapter.cvgrpc.GrpcDetectionPort;
import com.drones.vision.api.controller.ModelRegistryController;
import com.drones.vision.app.cv.DetectionPolicyCache;
import com.drones.vision.app.events.DetectionSessionCleanupEventPublisher;
import com.drones.vision.learning.application.ModelRegistryService;
import com.drones.vision.perception.domain.port.DetectionPort;
import com.drones.vision.platform.EventPublisherPort;
import com.drones.vision.learning.domain.port.ModelRegistryPort;
import io.grpc.ManagedChannel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * matches on {@code vision.cv.enabled} alone). See {@link TrainingEnabledWiringTest} for the
 * training-only mirror and {@link CvAndTrainingSharedChannelWiringTest} for the both-enabled case
 * that actually proves the channel is the <em>same instance</em> both ports consume.
 *
 * <p><b>The model registry now comes along for free</b> (docs/plans/active/CV-SETTINGS-PLAN.md §5,
 * CV-SETTINGS-CONTEXT.md's W4-app → W5 handoff): {@code vision.cv.registry.enabled}'s own
 * {@code application.yaml} default is the placeholder {@code ${vision.cv.enabled:false}}, so a
 * CV-only deployment that never touches the registry key still gets {@code
 * ModelRegistryController}/{@code ModelRegistryService}/{@code ModelRegistryPort} wired — a
 * deployment with detection on already has a cv-service worker to register models against. See
 * {@link CvRegistryExplicitOptOutWiringTest} for the explicit {@code
 * vision.cv.registry.enabled=false} escape hatch that keeps the pre-W5 "registry stays entirely
 * absent" behavior.
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
    private CvChannelSupervisor cvChannelSupervisor;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void enabledConfigurationSelectsGrpcDetectionPort() {
        assertInstanceOf(GrpcDetectionPort.class, detectionPort);
    }

    /**
     * docs/plans/active/CV-RECONNECT-PLAN.md wave R2: {@code vision.cv.reconnect.enabled} defaults to
     * {@code true}, so enabling CV alone is enough for {@link CvWiring#cvChannelSupervisor} to exist,
     * gating {@code detectionPort}'s three-arg {@code GrpcDetectionPort} constructor. See {@link
     * CvReconnectDisabledWiringTest} for the {@code false} counterpart.
     */
    @Test
    void enabledConfigurationBuildsTheCvChannelSupervisorByDefault() {
        assertInstanceOf(CvChannelSupervisor.class, cvChannelSupervisor);
    }

    @Test
    void enabledConfigurationWrapsEventPublisherWithSessionCleanupDecorator() {
        assertInstanceOf(DetectionSessionCleanupEventPublisher.class, eventPublisherPort);
    }

    @Test
    void cvOnlyConfigurationStillBuildsTheSharedChannel() {
        assertInstanceOf(ManagedChannel.class, cvGrpcChannel);
    }

    /**
     * docs/plans/active/CV-SETTINGS-PLAN.md §5: {@code vision.cv.registry.enabled} left unset
     * follows {@code vision.cv.enabled} via {@code application.yaml}'s {@code
     * ${vision.cv.enabled:false}} placeholder — a CV-only deployment (no {@code
     * vision.training.enabled}, no explicit registry key) wires the model registry anyway, unlike
     * before this wave.
     */
    @Test
    void cvOnlyConfigurationWiresTheModelRegistryByDefault() {
        assertFalse(applicationContext.getBeansOfType(ModelRegistryController.class).isEmpty());
        assertFalse(applicationContext.getBeansOfType(ModelRegistryService.class).isEmpty());
        assertFalse(applicationContext.getBeansOfType(ModelRegistryPort.class).isEmpty());
    }

    /**
     * docs/plans/active/ALWAYS-ON-FLOW-PLAN.md wave D1: {@code vision.cv.enabled=true} alone is
     * enough for {@link CvWiring#detectionPolicyCache} to exist — its own {@code
     * @ConditionalOnExpression} matches the same property this class already turns on, mirroring
     * {@link #enabledConfigurationBuildsTheCvChannelSupervisorByDefault()}'s own reasoning. See
     * {@link CvWiringTest#defaultConfigurationBuildsNoDetectionPolicyCache()} for the {@code false}
     * counterpart.
     */
    @Test
    void enabledConfigurationBuildsTheDetectionPolicyCache() {
        assertInstanceOf(DetectionPolicyCache.class, applicationContext.getBean(DetectionPolicyCache.class));
    }
}

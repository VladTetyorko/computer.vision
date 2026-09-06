package com.drones.vision.app;

import com.drones.vision.adapter.cvgrpc.CvChannelSupervisor;
import com.drones.vision.api.live.LiveAndPollDetectionDemand;
import com.drones.vision.api.support.StreamDetectionSupport;
import com.drones.vision.app.cv.DetectionPolicyCache;
import com.drones.vision.app.devsupport.LoggingEventPublisher;
import com.drones.vision.app.devsupport.NoopDetectionPort;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.perception.domain.port.DetectionPolicyPort;
import com.drones.vision.perception.domain.port.DetectionPort;
import com.drones.vision.platform.EventPublisherPort;
import io.grpc.ManagedChannel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Context test for the <em>default</em> {@code vision.cv.*} configuration (no override, per
 * {@link VisionCvProperties#enabled()}'s default of {@code false}): asserts {@link
 * WiringConfiguration} keeps today's behavior — {@link DetectionPort} stays the devsupport {@link
 * NoopDetectionPort}, and {@link EventPublisherPort} stays the plain {@link
 * LoggingEventPublisher} rather than being wrapped by {@link
 * DetectionSessionCleanupEventPublisher} (which only ever wraps it when CV is enabled — see
 * {@link CvEnabledWiringTest}).
 *
 * <p>{@code vision.publish.enabled=false} for the same determinism reasons as {@link
 * DiscoveryWiringTest}/{@link AssetWiringTest} — this test doesn't care about stream egress.
 * {@code vision.live.enabled=false} (docs/plans/done/REALTIME-PLAN.md §4) isolates this test from the
 * server-push feature's own {@code EventPublisherPort} decorator ({@link LiveUpdateEventPublisher}
 * — see {@link LiveWiringTest}/{@link LiveDisabledWiringTest} for that feature's own coverage), so
 * this class's assertions stay about CV wiring specifically, not about which other decorators
 * happen to also be layered on by default.
 */
@SpringBootTest(properties = {"vision.publish.enabled=false", "vision.live.enabled=false"})
class CvWiringTest {

    @Autowired
    private DetectionPort detectionPort;

    @Autowired
    private EventPublisherPort eventPublisherPort;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void defaultConfigurationKeepsNoopDetectionPort() {
        assertInstanceOf(NoopDetectionPort.class, detectionPort);
    }

    @Test
    void defaultConfigurationKeepsPlainLoggingEventPublisher() {
        assertInstanceOf(LoggingEventPublisher.class, eventPublisherPort);
    }

    /**
     * docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase 2 T9: with CV disabled (and {@code
     * vision.training.enabled} left at its default {@code false} too), {@link
     * WiringConfiguration#cvGrpcChannel} isn't built at all — no gRPC channel/executor overhead
     * beyond today's behavior. See {@link TrainingDisabledWiringTest} for the same assertion from
     * the training-flag's own perspective.
     */
    @Test
    void defaultConfigurationBuildsNoSharedCvGrpcChannel() {
        assertTrue(applicationContext.getBeansOfType(ManagedChannel.class).isEmpty());
    }

    /**
     * docs/plans/done/CV-DEMAND-PLAN.md §3.7/§3.8: {@code vision.cv.demand.enabled} defaults to
     * {@code true}, so the default-config context builds a real demand-poll port and
     * {@link StreamDetectionSupport} carries it — see {@link DetectionDemandDisabledWiringTest} for
     * the {@code false} counterpart.
     */
    @Test
    void defaultConfigurationWiresTheDetectionDemandPortIntoStreamDetectionSupport() {
        assertEquals(1, applicationContext.getBeansOfType(LiveAndPollDetectionDemand.class).size());
        StreamDetectionSupport support = applicationContext.getBean(StreamDetectionSupport.class);
        assertNotNull(support.demand());
    }

    /**
     * docs/plans/active/CV-RECONNECT-PLAN.md wave R2: with no {@code ManagedChannel} bean at all (see
     * {@link #defaultConfigurationBuildsNoSharedCvGrpcChannel()}), {@link CvWiring#cvChannelSupervisor}
     * can't exist either — its own {@code @ConditionalOnExpression} ANDs the same enabling properties
     * {@link CvWiring#cvGrpcChannel} checks with {@code vision.cv.reconnect.enabled} (default {@code
     * true}), so with every property at its default, the first half of that AND is already false.
     */
    @Test
    void defaultConfigurationBuildsNoCvChannelSupervisor() {
        assertTrue(applicationContext.getBeansOfType(CvChannelSupervisor.class).isEmpty());
    }

    /**
     * docs/plans/active/ALWAYS-ON-FLOW-PLAN.md wave D1: {@link CvWiring#detectionPolicyCache} is
     * gated on the same "CV switched on at all" expression {@link #defaultConfigurationBuildsNoCvChannelSupervisor}
     * exercises for {@link CvChannelSupervisor} — with every property at its default, that background
     * poller does not exist at all, not merely inert, so a deployment with CV entirely off pays zero
     * cost for a feature it never uses. See {@link CvEnabledWiringTest} for the {@code true} counterpart.
     */
    @Test
    void defaultConfigurationBuildsNoDetectionPolicyCache() {
        assertTrue(applicationContext.getBeansOfType(DetectionPolicyCache.class).isEmpty());
    }

    /**
     * {@link CvWiring#detectionPolicyPort}, unlike {@link CvWiring#detectionPolicyCache}, is wired
     * unconditionally (it costs nothing — no background thread, just a lambda over an {@code
     * ObjectProvider}) so {@code DefaultStreamService} always has something to consult; with the
     * cache absent, it must read every asset as {@code DetectionPolicy.ON_VIEW} (fail-closed, not
     * fail-open like {@link LiveAndPollDetectionDemand}) — the D1 acceptance bar that {@code ALWAYS}
     * stays strictly opt-in even in a deployment that never wires the cache at all.
     */
    @Test
    void defaultConfigurationWiresAnInertDetectionPolicyPort() {
        assertEquals(1, applicationContext.getBeansOfType(DetectionPolicyPort.class).size());
        DetectionPolicyPort policyPort = applicationContext.getBean(DetectionPolicyPort.class);
        assertFalse(policyPort.alwaysOn(AssetId.random()));
    }
}

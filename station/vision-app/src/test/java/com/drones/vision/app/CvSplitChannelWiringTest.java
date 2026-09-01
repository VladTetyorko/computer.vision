package com.drones.vision.app;

import com.drones.vision.adapter.cvgrpc.GrpcModelRegistryPort;
import com.drones.vision.adapter.cvgrpc.GrpcPulledGeolocationPort;
import com.drones.vision.adapter.cvgrpc.GrpcReferenceIndexPort;
import com.drones.vision.learning.domain.port.ModelRegistryPort;
import com.drones.vision.perception.domain.port.PulledGeolocationPort;
import com.drones.vision.perception.domain.port.ReferenceIndexPort;
import io.grpc.ManagedChannel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * Context test for a genuinely <em>split</em> cv-service deployment (docs/plans/active/
 * ARCHITECTURE-AUDIT-2026-08-26.md R6): {@code vision.cv.training.target} set alongside {@code
 * vision.cv.enabled}/{@code vision.training.enabled}/{@code vision.geo.visual.enabled}, so both
 * {@link CvWiring#cvGrpcChannel} (inference, {@code @Primary}) and {@link CvWiring#cvTrainingChannel}
 * exist together for the first time in this module's test suite.
 *
 * <p>Before this wave's routing fix, {@code TrainingWiringConfiguration}'s three consumers injected a
 * plain {@code ManagedChannel cvGrpcChannel} parameter, which — once {@link CvWiring#cvGrpcChannel} is
 * marked {@code @Primary} — silently keeps resolving the inference channel regardless of parameter
 * name (never a compile or startup error, so the training/geolocation RPCs would have silently ridden
 * the wrong channel); {@code VisualGeoWiringConfiguration}'s two consumers injected an <em>unqualified</em>
 * {@code ObjectProvider<ManagedChannel>}, which throws {@code NoUniqueBeanDefinitionException} on
 * {@code getObject()} as soon as a second {@code ManagedChannel} bean exists — a genuine context-startup
 * break. This test's mere green run is therefore load-bearing: a context that fails to start (or fails
 * one of the calls below) means the routing regressed.
 *
 * <p>No live cv-service is needed for either channel — a {@link ManagedChannel} only connects lazily,
 * on first RPC, so both targets below (arbitrary unused loopback ports) never need anything listening.
 */
@SpringBootTest(properties = {
        "vision.publish.enabled=false",
        "vision.live.enabled=false",
        "vision.cv.enabled=true",
        "vision.cv.endpoint=localhost:59500",
        "vision.training.enabled=true",
        "vision.geo.visual.enabled=true",
        "vision.cv.training.target=localhost:59501"
})
class CvSplitChannelWiringTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    @Qualifier("cvGrpcChannel")
    private ManagedChannel cvGrpcChannel;

    @Autowired
    @Qualifier("cvTrainingChannel")
    private ManagedChannel cvTrainingChannel;

    @Autowired
    private PulledGeolocationPort pulledGeolocationPort;

    @Autowired
    private ReferenceIndexPort referenceIndexPort;

    @Autowired
    private ModelRegistryPort modelRegistryPort;

    /** Both channels exist, and they are two distinct beans — not the same instance twice. */
    @Test
    void bothTheInferenceAndTrainingChannelBeansExist() {
        Map<String, ManagedChannel> channels = applicationContext.getBeansOfType(ManagedChannel.class);
        assertEquals(2, channels.size());
    }

    /**
     * The R6 fix under test: with {@code cvTrainingChannel} present, the two geolocation-side
     * consumers no longer throw {@code NoUniqueBeanDefinitionException} resolving their (formerly
     * unqualified) {@code ObjectProvider<ManagedChannel>} — they resolve to real gRPC-backed ports.
     */
    @Test
    void geolocationPortsResolveToTheirGrpcImplementations() {
        assertInstanceOf(GrpcPulledGeolocationPort.class, pulledGeolocationPort);
        assertInstanceOf(GrpcReferenceIndexPort.class, referenceIndexPort);
    }

    /**
     * The R6 fix under test: {@code TrainingWiringConfiguration#modelRegistryPort} now routes through
     * {@code CvWiring#controlPlaneChannel} instead of a name-shadowed plain {@code ManagedChannel}
     * parameter that would have silently kept resolving the {@code @Primary} inference channel.
     */
    @Test
    void modelRegistryPortResolvesToItsGrpcImplementation() {
        assertInstanceOf(GrpcModelRegistryPort.class, modelRegistryPort);
    }

    /** Sanity: the two autowired channel beans really are the distinct inference/training instances. */
    @Test
    void theTwoQualifiedChannelBeansAreDistinct() {
        assertEquals(2, applicationContext.getBeansOfType(ManagedChannel.class).size());
        assertNotSame(cvGrpcChannel, cvTrainingChannel);
    }
}

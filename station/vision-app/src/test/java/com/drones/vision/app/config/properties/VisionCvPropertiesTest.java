package com.drones.vision.app.config.properties;

import com.drones.vision.adapter.cvgrpc.CvTarget;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain unit tests (no Spring context) for {@link VisionCvProperties}'s compact-constructor
 * validation and {@link VisionCvProperties#host()}/{@link VisionCvProperties#port()} parsing —
 * mirrors this module's other no-context record/port-behavior test classes (e.g. {@code
 * SimulationResumeRunnerTest}) rather than requiring a full {@code @SpringBootTest} just to
 * exercise a record's own constructor.
 */
class VisionCvPropertiesTest {

    @Test
    void hostAndPortParseOutOfEndpoint() {
        VisionCvProperties properties = new VisionCvProperties(true, "example.org:50051", 640, 0.8f);
        assertEquals("example.org", properties.host());
        assertEquals(50051, properties.port());
    }

    @Test
    void blankEndpointIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new VisionCvProperties(true, " ", 640, 0.8f));
    }

    @Test
    void detectWidthBelowMinimumIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new VisionCvProperties(true, "localhost:50051", 63, 0.8f));
        assertEquals("vision.cv.detect-width must be >= 64, was 63", ex.getMessage());
    }

    @Test
    void detectWidthAtMinimumIsAccepted() {
        assertDoesNotThrow(() -> new VisionCvProperties(true, "localhost:50051", 64, 0.8f));
    }

    @Test
    void jpegQualityOfZeroIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new VisionCvProperties(true, "localhost:50051", 640, 0f));
        assertEquals("vision.cv.jpeg-quality must be in (0,1], was 0.0", ex.getMessage());
    }

    @Test
    void jpegQualityAboveOneIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new VisionCvProperties(true, "localhost:50051", 640, 1.01f));
    }

    @Test
    void jpegQualityAtUpperBoundIsAccepted() {
        assertDoesNotThrow(() -> new VisionCvProperties(true, "localhost:50051", 640, 1.0f));
    }

    @Test
    void defaultsMatchApplicationPropertiesDocumentedValues() {
        // VisionCvProperties is only ever constructed with all four components explicitly (this
        // record has no no-arg form) -- this test pins the *values* the @DefaultValue
        // annotations declare (see the class itself), which is what application.yaml'
        // commented vision.cv.detect-width/vision.cv.jpeg-quality lines document as "the default".
        VisionCvProperties properties = new VisionCvProperties(false, "localhost:50051", 640, 0.8f);
        assertEquals(640, properties.detectWidth());
        assertEquals(0.8f, properties.jpegQuality());
    }

    @Test
    void detectionDefaultEnabledDefaultsToFalseThroughTheConvenienceConstructor() {
        // docs/plans/done/CV-DEMAND-PLAN.md §3.7: a deployment that never sets this stays on
        // today's behavior -- a new stream starts with detection off until something asks for it.
        VisionCvProperties properties = new VisionCvProperties(false, "localhost:50051", 640, 0.8f);
        assertFalse(properties.detectionDefaultEnabled());
    }

    @Test
    void demandDefaultsToEnabledWithTheDocumentedTunablesWhenAbsent() {
        // The compact constructor substitutes a fully-defaulted Demand when the @NestedConfigurationProperty
        // is absent -- this pins the exact values application.yaml's commented vision.cv.demand.* block
        // documents as "the default".
        VisionCvProperties properties = new VisionCvProperties(false, "localhost:50051", 640, 0.8f);
        assertTrue(properties.demand().enabled());
        assertEquals(Duration.ofSeconds(2), properties.demand().pollInterval());
        assertEquals(Duration.ofSeconds(30), properties.demand().grace());
        assertEquals(Duration.ofSeconds(10), properties.demand().pollTtl());
    }

    /**
     * docs/plans/active/CV-RECONNECT-PLAN.md §3.3, wave R2: the four-arg convenience constructor
     * passes {@code null} for {@link VisionCvProperties.Reconnect} same as it does for {@code
     * Upload}/{@code Registry}/{@code Pull} -- the compact constructor must default it as a whole,
     * matching every sibling nested record's "defaulted as a whole when absent" contract.
     */
    @Test
    void reconnectDefaultsWhenAbsent() {
        VisionCvProperties properties = new VisionCvProperties(false, "localhost:50051", 640, 0.8f);
        assertTrue(properties.reconnect().enabled());
        assertEquals(Duration.ofSeconds(1), properties.reconnect().initialBackoff());
        assertEquals(Duration.ofSeconds(10), properties.reconnect().maxBackoff());
        assertEquals(Duration.ofSeconds(60), properties.reconnect().outageLogInterval());
    }

    /** The canonical (compact) constructor accepts an explicit {@link VisionCvProperties.Reconnect} unchanged. */
    @Test
    void reconnectExplicitValueIsNotOverridden() {
        VisionCvProperties.Reconnect reconnect =
                new VisionCvProperties.Reconnect(false, Duration.ofMillis(200), Duration.ofSeconds(5),
                        Duration.ofSeconds(30));
        VisionCvProperties properties = new VisionCvProperties(false, "localhost:50051", 640, 0.8f, "auto", "push",
                Duration.ofSeconds(2), Duration.ofSeconds(20), Duration.ofSeconds(5), true, Duration.ofSeconds(5),
                true, null, null, null, false, null, reconnect, null, null, null, null);
        assertEquals(reconnect, properties.reconnect());
    }

    /**
     * docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R6: with no {@code vision.cv.inference.targets}
     * configured (the default, via the 4-arg convenience constructor), {@link
     * VisionCvProperties#inferenceTargets()} falls back to a single {@link CvTarget} built from
     * {@link VisionCvProperties#host()}/{@link VisionCvProperties#port()} -- a one-process deployment
     * gets exactly the endpoint it configured, not an empty list.
     */
    @Test
    void inferenceTargetsFallsBackToHostAndPortWhenUnset() {
        VisionCvProperties properties = new VisionCvProperties(true, "example.org:50051", 640, 0.8f);
        assertEquals(List.of(new CvTarget("example.org", 50051)), properties.inferenceTargets());
    }

    /** An explicit {@code vision.cv.inference.targets} list is parsed in order via {@code CvTarget#parseAll}. */
    @Test
    void inferenceTargetsParsesTheConfiguredListWhenPresent() {
        VisionCvProperties.Inference inference = new VisionCvProperties.Inference(List.of("a:1", "b:2"));
        VisionCvProperties properties = new VisionCvProperties(false, "localhost:50051", 640, 0.8f, "auto", "push",
                Duration.ofSeconds(2), Duration.ofSeconds(20), Duration.ofSeconds(5), true, Duration.ofSeconds(5),
                true, null, null, null, false, null, null, inference, null, null, null);
        assertEquals(List.of(new CvTarget("a", 1), new CvTarget("b", 2)), properties.inferenceTargets());
    }

    /** A malformed {@code vision.cv.inference.targets} entry fails at context startup, not at first RPC. */
    @Test
    void malformedInferenceTargetIsRejectedAtConstruction() {
        VisionCvProperties.Inference inference = new VisionCvProperties.Inference(List.of("not-a-target"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new VisionCvProperties(false, "localhost:50051", 640, 0.8f, "auto", "push",
                        Duration.ofSeconds(2), Duration.ofSeconds(20), Duration.ofSeconds(5), true,
                        Duration.ofSeconds(5), true, null, null, null, false, null, null, inference, null, null, null));
        assertTrue(ex.getMessage().contains("vision.cv.inference.targets"), ex.getMessage());
    }

    /**
     * docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R6: with no {@code vision.cv.training.target}
     * configured (the default), {@link VisionCvProperties#trainingTarget()} falls back to {@link
     * VisionCvProperties#host()}/{@link VisionCvProperties#port()} -- the same one-process fallback
     * {@link #inferenceTargetsFallsBackToHostAndPortWhenUnset()} exercises for the inference side --
     * and {@link VisionCvProperties#trainingTargetConfigured()} reports {@code false}.
     */
    @Test
    void trainingTargetFallsBackToHostAndPortWhenUnset() {
        VisionCvProperties properties = new VisionCvProperties(true, "example.org:50051", 640, 0.8f);
        assertEquals(new CvTarget("example.org", 50051), properties.trainingTarget());
        assertFalse(properties.trainingTargetConfigured());
    }

    /** An explicit {@code vision.cv.training.target} is parsed via {@code CvTarget#parse} and reported configured. */
    @Test
    void trainingTargetParsesTheConfiguredValueWhenPresent() {
        VisionCvProperties.Training training = new VisionCvProperties.Training("cv-training-host:50062");
        VisionCvProperties properties = new VisionCvProperties(false, "localhost:50051", 640, 0.8f, "auto", "push",
                Duration.ofSeconds(2), Duration.ofSeconds(20), Duration.ofSeconds(5), true, Duration.ofSeconds(5),
                true, null, null, null, false, null, null, null, training, null, null);
        assertEquals(new CvTarget("cv-training-host", 50062), properties.trainingTarget());
        assertTrue(properties.trainingTargetConfigured());
    }

    /** A malformed {@code vision.cv.training.target} fails at context startup, not at first RPC. */
    @Test
    void malformedTrainingTargetIsRejectedAtConstruction() {
        VisionCvProperties.Training training = new VisionCvProperties.Training("not-a-target");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new VisionCvProperties(false, "localhost:50051", 640, 0.8f, "auto", "push",
                        Duration.ofSeconds(2), Duration.ofSeconds(20), Duration.ofSeconds(5), true,
                        Duration.ofSeconds(5), true, null, null, null, false, null, null, null, training, null, null));
        assertTrue(ex.getMessage().contains("vision.cv.training.target"), ex.getMessage());
    }
}

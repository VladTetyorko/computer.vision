package com.drones.vision.app.config.properties;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain unit tests (no Spring context) for {@link VisionCvProperties}'s compact-constructor
 * validation and {@link VisionCvProperties#host()}/{@link VisionCvProperties#port()} parsing —
 * mirrors this module's other no-context record/port-behavior test classes (e.g. {@code
 * devsupport.InMemoryDetectionEventRepositoryTest}) rather than requiring a full {@code
 * @SpringBootTest} just to exercise a record's own constructor.
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
                true, null, null, null, reconnect);
        assertEquals(reconnect, properties.reconnect());
    }
}

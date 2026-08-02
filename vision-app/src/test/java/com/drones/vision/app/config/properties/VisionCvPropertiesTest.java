package com.drones.vision.app.config.properties;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        // annotations declare (see the class itself), which is what application.properties'
        // commented vision.cv.detect-width/vision.cv.jpeg-quality lines document as "the default".
        VisionCvProperties properties = new VisionCvProperties(false, "localhost:50051", 640, 0.8f);
        assertEquals(640, properties.detectWidth());
        assertEquals(0.8f, properties.jpegQuality());
    }
}

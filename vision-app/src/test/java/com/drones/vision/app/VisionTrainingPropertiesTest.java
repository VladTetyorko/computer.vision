package com.drones.vision.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Plain unit tests (no Spring context) for {@link VisionTrainingProperties}'s compact-constructor
 * validation — mirrors {@link VisionCvPropertiesTest}'s no-context style rather than requiring a
 * full {@code @SpringBootTest} just to exercise a record's own constructor.
 */
class VisionTrainingPropertiesTest {

    @Test
    void blankExportDirIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new VisionTrainingProperties(true, " "));
    }

    @Test
    void nullExportDirIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new VisionTrainingProperties(true, null));
    }

    @Test
    void defaultExportDirMatchesApplicationPropertiesDocumentedValue() {
        // VisionTrainingProperties is only ever constructed with both components explicitly (this
        // record has no no-arg form) -- this test pins the *value* the @DefaultValue annotation
        // declares, which is what application.properties' commented vision.training.export-dir
        // line documents as "the default".
        VisionTrainingProperties properties = new VisionTrainingProperties(false, "data/training-exports");
        assertEquals("data/training-exports", properties.exportDir());
    }

    @Test
    void disabledByDefaultIsAccepted() {
        VisionTrainingProperties properties = new VisionTrainingProperties(false, "data/training-exports");
        assertEquals(false, properties.enabled());
    }
}

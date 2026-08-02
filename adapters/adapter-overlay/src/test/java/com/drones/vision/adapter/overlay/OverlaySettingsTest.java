package com.drones.vision.adapter.overlay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OverlaySettingsTest {

    @Test
    void defaultsMatchTheLiteralsTheyReplaced() {
        OverlaySettings settings = OverlaySettings.defaults();

        assertEquals(0.8f, settings.jpegQuality());
        assertEquals(2, settings.minStrokeWidth());
        assertEquals(200, settings.strokeDivisor());
        assertEquals(12, settings.minFontSize());
        assertEquals(45, settings.fontDivisor());
        assertEquals(160, settings.osdBackgroundAlpha());
        assertEquals(4, settings.osdMargin());
    }

    @Test
    void rejectsJpegQualityOutsideZeroExclusiveToOneInclusive() {
        assertThrows(IllegalArgumentException.class, () -> new OverlaySettings(0f, 2, 200, 12, 45, 160, 4));
        assertThrows(IllegalArgumentException.class, () -> new OverlaySettings(1.1f, 2, 200, 12, 45, 160, 4));
    }

    @Test
    void rejectsNonPositiveDivisorsAndSizes() {
        assertThrows(IllegalArgumentException.class, () -> new OverlaySettings(0.8f, 0, 200, 12, 45, 160, 4));
        assertThrows(IllegalArgumentException.class, () -> new OverlaySettings(0.8f, 2, 0, 12, 45, 160, 4));
        assertThrows(IllegalArgumentException.class, () -> new OverlaySettings(0.8f, 2, 200, 0, 45, 160, 4));
        assertThrows(IllegalArgumentException.class, () -> new OverlaySettings(0.8f, 2, 200, 12, 0, 160, 4));
    }

    @Test
    void rejectsAlphaOutsideByteRange() {
        assertThrows(IllegalArgumentException.class, () -> new OverlaySettings(0.8f, 2, 200, 12, 45, -1, 4));
        assertThrows(IllegalArgumentException.class, () -> new OverlaySettings(0.8f, 2, 200, 12, 45, 256, 4));
    }

    @Test
    void rejectsNegativeMargin() {
        assertThrows(IllegalArgumentException.class, () -> new OverlaySettings(0.8f, 2, 200, 12, 45, 160, -1));
    }
}

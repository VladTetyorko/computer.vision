package com.drones.vision.app.config.properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Plain unit tests (no Spring context) for {@link VisionOpsProperties}'s compact-constructor
 * validation and defaulting — mirrors {@link VisionMavlinkPropertiesTest}'s own no-context style for
 * a record-plus-{@code @DefaultValue} properties class with a nested, sometimes-absent record.
 */
class VisionOpsPropertiesTest {

    @Test
    void explicitBatteryThresholdsAreCarriedThrough() {
        VisionOpsProperties properties = new VisionOpsProperties(new VisionOpsProperties.Battery(30, 15));

        assertEquals(30, properties.battery().warningPercent());
        assertEquals(15, properties.battery().criticalPercent());
    }

    @Test
    void anAbsentBatteryBlockDefaultsToTwentyFiveAndTen() {
        VisionOpsProperties properties = new VisionOpsProperties(null);

        assertEquals(25, properties.battery().warningPercent());
        assertEquals(10, properties.battery().criticalPercent());
    }

    @Test
    void criticalPercentEqualToWarningPercentIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new VisionOpsProperties.Battery(25, 25));
        assertEquals("vision.ops.battery.critical-percent must be < warning-percent: 25 >= 25", ex.getMessage());
    }

    @Test
    void criticalPercentAboveWarningPercentIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new VisionOpsProperties.Battery(10, 25));
    }

    @Test
    void negativeWarningPercentIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new VisionOpsProperties.Battery(-1, -5));
    }

    @Test
    void warningPercentAboveOneHundredIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new VisionOpsProperties.Battery(101, 10));
    }

    @Test
    void negativeCriticalPercentIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new VisionOpsProperties.Battery(25, -1));
    }

    @Test
    void criticalPercentBelowWarningPercentIsAccepted() {
        assertDoesNotThrow(() -> new VisionOpsProperties.Battery(25, 10));
    }

    /**
     * Bound from an <em>empty</em> source, so what this test sees is exactly what a deployment with
     * no {@code vision.ops.battery} block at all gets — the {@code @DefaultValue} annotations plus
     * the compact constructor's own null-fallback, not a hand-written {@code new} call. Pins
     * application.yaml's documented defaults (25/10).
     */
    @Test
    void defaultsMatchApplicationYamlsDocumentedValues() {
        VisionOpsProperties bound =
                new Binder(new MapConfigurationPropertySource(Map.of())).bindOrCreate("vision.ops", VisionOpsProperties.class);

        assertEquals(25, bound.battery().warningPercent());
        assertEquals(10, bound.battery().criticalPercent());
    }

    /**
     * Same idiom, but only {@code vision.ops.battery.warning-percent} is present — proves the nested
     * {@code Battery} record's own field-level {@code @DefaultValue} still applies to
     * {@code criticalPercent} once the block is no longer entirely absent (distinct from the
     * whole-block-absent case above, which instead falls back to {@code Battery.defaults()}).
     */
    @Test
    void onePresentKeyStillDefaultsTheOther() {
        VisionOpsProperties bound = new Binder(
                new MapConfigurationPropertySource(Map.of("vision.ops.battery.warning-percent", "40")))
                .bindOrCreate("vision.ops", VisionOpsProperties.class);

        assertEquals(40, bound.battery().warningPercent());
        assertEquals(10, bound.battery().criticalPercent());
    }
}

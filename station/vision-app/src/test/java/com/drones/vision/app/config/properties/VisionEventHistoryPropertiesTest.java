package com.drones.vision.app.config.properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain unit tests (no Spring context) for {@link VisionEventHistoryProperties}'s compact-
 * constructor defaulting and {@link VisionEventHistoryProperties.Retention}'s validation — mirrors
 * {@link VisionOpsPropertiesTest}'s own no-context style for a record-plus-{@code @DefaultValue}
 * properties class with a nested, sometimes-absent record.
 */
class VisionEventHistoryPropertiesTest {

    @Test
    void anAbsentRetentionBlockDefaultsToTwentyThousandRows() {
        VisionEventHistoryProperties properties = new VisionEventHistoryProperties(true, null);

        assertEquals(20_000, properties.retention().maxRows());
    }

    @Test
    void explicitRetentionIsCarriedThrough() {
        VisionEventHistoryProperties properties =
                new VisionEventHistoryProperties(true, new VisionEventHistoryProperties.Retention(500));

        assertEquals(500, properties.retention().maxRows());
    }

    @Test
    void zeroMaxRowsIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new VisionEventHistoryProperties.Retention(0));
    }

    @Test
    void negativeMaxRowsIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new VisionEventHistoryProperties.Retention(-1));
    }

    /**
     * Bound from an <em>empty</em> source, so what this test sees is exactly what a deployment with
     * no {@code vision.events.history} block at all gets — the {@code @DefaultValue} annotations plus
     * the compact constructor's own null-fallback, not a hand-written {@code new} call. Pins
     * application.yaml's documented default: the opt-in guardrail (disabled by default) plus a
     * 20,000-row cap once enabled.
     */
    @Test
    void defaultsMatchApplicationYamlsDocumentedValues() {
        VisionEventHistoryProperties bound = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("vision.events.history", VisionEventHistoryProperties.class);

        assertFalse(bound.enabled());
        assertEquals(20_000, bound.retention().maxRows());
    }

    @Test
    void explicitEnabledAndMaxRowsBindFromConfiguration() {
        VisionEventHistoryProperties bound = new Binder(new MapConfigurationPropertySource(
                Map.of("vision.events.history.enabled", "true",
                        "vision.events.history.retention.max-rows", "5000")))
                .bindOrCreate("vision.events.history", VisionEventHistoryProperties.class);

        assertTrue(bound.enabled());
        assertEquals(5000, bound.retention().maxRows());
    }
}

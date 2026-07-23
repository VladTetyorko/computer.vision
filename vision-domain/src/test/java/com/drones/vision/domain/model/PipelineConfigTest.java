package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineConfigTest {

    @Test
    void defaultsMatchSpec() {
        PipelineConfig defaults = PipelineConfig.defaults();

        assertEquals(new ModelRef("yolo", "latest"), defaults.model());
        assertEquals(0.4, defaults.confidenceThreshold());
        assertEquals(10, defaults.inferenceFps());
        assertEquals(2, defaults.maxInFlightInferences());
        assertTrue(defaults.overlayTelemetry());
        assertTrue(defaults.labelFilter().isEmpty(), "empty labelFilter means all labels");
        assertEquals(EventRuleConfig.defaults(), defaults.eventRule());
    }

    @Test
    void sixArgConvenienceConstructorDefaultsEventRule() {
        PipelineConfig config = new PipelineConfig(new ModelRef("yolo", "1"), 0.5, 5, 2, true, Set.of());

        assertEquals(EventRuleConfig.defaults(), config.eventRule());
    }

    @Test
    void sevenArgConstructorAcceptsAnExplicitEventRule() {
        EventRuleConfig customRule = new EventRuleConfig(Set.of("dog"), 0.7, 5, Duration.ofSeconds(10));

        PipelineConfig config = new PipelineConfig(new ModelRef("yolo", "1"), 0.5, 5, 2, true, Set.of(), customRule);

        assertEquals(customRule, config.eventRule());
    }

    @Test
    void rejectsNullEventRule() {
        ModelRef model = new ModelRef("yolo", "1");

        assertThrows(IllegalArgumentException.class,
                () -> new PipelineConfig(model, 0.5, 5, 2, true, Set.of(), null));
    }

    @Test
    void labelFilterIsDefensivelyCopied() {
        Set<String> labels = new HashSet<>();
        labels.add("person");

        PipelineConfig config = new PipelineConfig(new ModelRef("yolo", "1"), 0.5, 5, 2, true, labels);

        labels.add("car");

        assertEquals(1, config.labelFilter().size());
        assertThrows(UnsupportedOperationException.class, () -> config.labelFilter().add("dog"));
    }

    @Test
    void rejectsInvalidArguments() {
        ModelRef model = new ModelRef("yolo", "1");

        assertThrows(IllegalArgumentException.class, () -> new PipelineConfig(null, 0.5, 5, 2, true, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new PipelineConfig(model, -0.01, 5, 2, true, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new PipelineConfig(model, 1.01, 5, 2, true, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new PipelineConfig(model, 0.5, 0, 2, true, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new PipelineConfig(model, 0.5, 5, 0, true, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new PipelineConfig(model, 0.5, 5, 2, true, null));
    }

    @Test
    void twoDefaultsCallsAreEqualValues() {
        assertEquals(PipelineConfig.defaults(), PipelineConfig.defaults());
    }
}

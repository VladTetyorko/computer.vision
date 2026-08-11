package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineConfigTest {

    @Test
    void defaultsMatchSpec() {
        PipelineConfig defaults = PipelineConfig.defaults();

        // docs/CV-CONTROL-PLAN.md §1/§B: fixes the previously-dead "yolo" model id (matched no
        // real checkpoint, silently relied on cv-service's own fallback) to the real checkpoint
        // id cv-service already defaults to.
        assertEquals(new ModelRef("yolo26n.pt", "latest"), defaults.model());
        assertEquals(0.4, defaults.confidenceThreshold());
        assertEquals(10, defaults.inferenceFps());
        assertEquals(2, defaults.maxInFlightInferences());
        assertTrue(defaults.overlayTelemetry());
        assertTrue(defaults.labelFilter().isEmpty(), "empty labelFilter means all labels");
        assertEquals(EventRuleConfig.defaults(), defaults.eventRule());
        assertTrue(defaults.overlayBurnIn(), "overlay burn-in defaults on, unchanged behavior");
        assertTrue(defaults.detectionEnabled(), "detection defaults on, unchanged behavior");
        // docs/TRACKING-PLAN.md §5.G / §6.C: PipelineConfig.defaults() ships TrackingConfig.off()
        // through waves T2-T7, deliberately NOT TrackingConfig.defaults() (ASSOCIATE) - the flip
        // is wave T8's own single, reviewable commit. Do not "fix" this assertion before T8.
        assertEquals(TrackingMode.OFF, defaults.tracking().mode(), "tracking defaults OFF until wave T8");
    }

    @Test
    void sixArgConvenienceConstructorDefaultsEventRuleOverlayBurnInAndDetectionEnabled() {
        PipelineConfig config = new PipelineConfig(new ModelRef("yolo", "1"), 0.5, 5, 2, true, Set.of());

        assertEquals(EventRuleConfig.defaults(), config.eventRule());
        assertTrue(config.overlayBurnIn());
        assertTrue(config.detectionEnabled(), "old 6-arg ctor chain still defaults detectionEnabled=true");
        assertEquals(TrackingConfig.off(), config.tracking(), "old 6-arg ctor chain still defaults tracking=off");
    }

    @Test
    void sevenArgConstructorAcceptsAnExplicitEventRuleAndDefaultsOverlayBurnInAndDetectionEnabled() {
        EventRuleConfig customRule = new EventRuleConfig(Set.of("dog"), 0.7, 5, Duration.ofSeconds(10));

        PipelineConfig config = new PipelineConfig(new ModelRef("yolo", "1"), 0.5, 5, 2, true, Set.of(), customRule);

        assertEquals(customRule, config.eventRule());
        assertTrue(config.overlayBurnIn());
        assertTrue(config.detectionEnabled(), "old 7-arg ctor chain still defaults detectionEnabled=true");
        assertEquals(TrackingConfig.off(), config.tracking(), "old 7-arg ctor chain still defaults tracking=off");
    }

    @Test
    void eightArgConstructorAcceptsAnExplicitOverlayBurnInAndDefaultsDetectionEnabled() {
        EventRuleConfig customRule = EventRuleConfig.defaults();

        PipelineConfig config =
                new PipelineConfig(new ModelRef("yolo", "1"), 0.5, 5, 2, true, Set.of(), customRule, false);

        assertEquals(customRule, config.eventRule());
        assertFalse(config.overlayBurnIn());
        assertTrue(config.detectionEnabled(),
                "old 8-arg canonical ctor (pre-Wave-B) still defaults detectionEnabled=true");
        assertEquals(TrackingConfig.off(), config.tracking(), "old 8-arg ctor chain still defaults tracking=off");
    }

    @Test
    void nineArgConstructorRoundTripsAnExplicitDetectionEnabledAndDefaultsTracking() {
        EventRuleConfig customRule = EventRuleConfig.defaults();

        PipelineConfig config =
                new PipelineConfig(new ModelRef("yolo", "1"), 0.5, 5, 2, true, Set.of(), customRule, false, false);

        assertEquals(customRule, config.eventRule());
        assertFalse(config.overlayBurnIn());
        assertFalse(config.detectionEnabled());
        assertEquals(TrackingConfig.off(), config.tracking(),
                "old 9-arg canonical ctor (pre-TRACKING-PLAN) now defaults tracking=off");
    }

    @Test
    void tenArgCanonicalConstructorRoundTripsAnExplicitTracking() {
        EventRuleConfig customRule = EventRuleConfig.defaults();
        TrackingConfig tracking = TrackingConfig.defaults();

        PipelineConfig config = new PipelineConfig(
                new ModelRef("yolo", "1"), 0.5, 5, 2, true, Set.of(), customRule, false, false, tracking);

        assertEquals(tracking, config.tracking());
    }

    @Test
    void rejectsNullEventRule() {
        ModelRef model = new ModelRef("yolo", "1");

        assertThrows(IllegalArgumentException.class,
                () -> new PipelineConfig(model, 0.5, 5, 2, true, Set.of(), null, true, true));
    }

    @Test
    void rejectsNullTracking() {
        ModelRef model = new ModelRef("yolo", "1");

        assertThrows(IllegalArgumentException.class, () -> new PipelineConfig(
                model, 0.5, 5, 2, true, Set.of(), EventRuleConfig.defaults(), true, true, null));
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

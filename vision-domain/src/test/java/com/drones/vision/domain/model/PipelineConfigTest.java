package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineConfigTest {

    @Test
    void defaultsMatchSpec() {
        PipelineConfig defaults = PipelineConfig.defaults();

        // docs/plans/done/CV-CONTROL-PLAN.md §1/§B: fixes the previously-dead "yolo" model id (matched no
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
        // docs/plans/done/TRACKING-PLAN.md §5.G, wave T8: this is the flip. PipelineConfig.defaults() shipped
        // TrackingConfig.off() through waves T2-T7 so every pre-tracking test stayed green while the
        // chain was built; T8 turns it on, and this assertion is the line that says so. A new stream
        // associates, so every detection it produces carries a stable trackId.
        assertEquals(TrackingConfig.defaults(), defaults.tracking(), "tracking defaults ASSOCIATE as of wave T8");
        assertEquals(TrackingMode.ASSOCIATE, defaults.tracking().mode());
        assertNull(defaults.tracking().lock(), "a default stream holds no target -- FOLLOW is what locks");
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
        // Wave T8 flipped defaults() to ASSOCIATE but deliberately left the convenience ctors on
        // off(): "the component you did not mention keeps its pre-existing value" is a different
        // contract from "what a new stream should be", and conflating them would turn tracking on
        // for every call site that merely predates the field.
        assertEquals(TrackingConfig.off(), config.tracking(),
                "old 9-arg canonical ctor (pre-TRACKING-PLAN) still defaults tracking=off");
        assertNotEquals(PipelineConfig.defaults().tracking(), config.tracking(),
                "and that is deliberately NOT what defaults() ships as of wave T8");
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

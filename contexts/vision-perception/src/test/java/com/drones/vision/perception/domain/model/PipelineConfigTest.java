package com.drones.vision.perception.domain.model;

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
        assertTrue(defaults.labelFilter().isEmpty(), "empty labelFilter means all labels");
        assertTrue(defaults.labelDenyFilter().isEmpty(), "empty labelDenyFilter means deny nothing");
        assertEquals(EventRuleConfig.defaults(), defaults.eventRule());
        // docs/plans/done/CV-DEMAND-PLAN.md §1, wave D1: the flip. A new stream is video-only until an
        // operator turns detection on for it, so many concurrent streams stay affordable by default.
        assertFalse(defaults.detectionEnabled(), "detection defaults off as of CV-DEMAND-PLAN wave D1");
        // docs/plans/done/TRACKING-PLAN.md §5.G, wave T8: this is the flip. PipelineConfig.defaults() shipped
        // TrackingConfig.off() through waves T2-T7 so every pre-tracking test stayed green while the
        // chain was built; T8 turns it on, and this assertion is the line that says so. A new stream
        // associates, so every detection it produces carries a stable trackId.
        assertEquals(TrackingConfig.defaults(), defaults.tracking(), "tracking defaults ASSOCIATE as of wave T8");
        assertEquals(TrackingMode.ASSOCIATE, defaults.tracking().mode());
        assertNull(defaults.tracking().lock(), "a default stream holds no target -- FOLLOW is what locks");
        assertFalse(defaults.trace(),
                "trace defaults off (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.4); wave W2's "
                        + "TraceDemand is what flips it per-stream, not a platform default");
    }

    @Test
    void fiveArgConvenienceConstructorDefaultsEventRuleDetectionEnabledTrackingLabelDenyFilterAndTrace() {
        PipelineConfig config = new PipelineConfig(new ModelRef("yolo", "1"), 0.5, 5, 2, Set.of());

        assertEquals(EventRuleConfig.defaults(), config.eventRule());
        assertFalse(config.detectionEnabled(),
                "5-arg ctor chain defaults detectionEnabled=false (docs/plans/done/CV-DEMAND-PLAN.md §1, wave D1)");
        assertEquals(TrackingConfig.off(), config.tracking(), "5-arg ctor chain still defaults tracking=off");
        assertTrue(config.labelDenyFilter().isEmpty(),
                "5-arg ctor chain defaults labelDenyFilter to empty (deny nothing)");
        assertFalse(config.trace(), "5-arg ctor chain defaults trace off too");
    }

    @Test
    void sixArgConstructorAcceptsAnExplicitEventRuleAndDefaultsDetectionEnabledTrackingLabelDenyFilterAndTrace() {
        EventRuleConfig customRule = new EventRuleConfig(Set.of("dog"), 0.7, 5, Duration.ofSeconds(10));

        PipelineConfig config = new PipelineConfig(new ModelRef("yolo", "1"), 0.5, 5, 2, Set.of(), customRule);

        assertEquals(customRule, config.eventRule());
        assertFalse(config.detectionEnabled(),
                "6-arg ctor chain defaults detectionEnabled=false (docs/plans/done/CV-DEMAND-PLAN.md §1, wave D1)");
        assertEquals(TrackingConfig.off(), config.tracking(), "6-arg ctor chain still defaults tracking=off");
        assertTrue(config.labelDenyFilter().isEmpty());
        assertFalse(config.trace(), "6-arg ctor chain defaults trace off too");
    }

    @Test
    void sevenArgConstructorAcceptsAnExplicitDetectionEnabledAndDefaultsTrackingLabelDenyFilterAndTrace() {
        EventRuleConfig customRule = EventRuleConfig.defaults();

        PipelineConfig config =
                new PipelineConfig(new ModelRef("yolo", "1"), 0.5, 5, 2, Set.of(), customRule, true);

        assertEquals(customRule, config.eventRule());
        assertTrue(config.detectionEnabled());
        assertEquals(TrackingConfig.off(), config.tracking(), "7-arg ctor chain still defaults tracking=off");
        assertTrue(config.labelDenyFilter().isEmpty());
        assertFalse(config.trace(), "7-arg ctor chain defaults trace off too");
    }

    @Test
    void eightArgConstructorRoundTripsAnExplicitTrackingAndDefaultsLabelDenyFilterAndTrace() {
        EventRuleConfig customRule = EventRuleConfig.defaults();
        TrackingConfig tracking = TrackingConfig.defaults();

        PipelineConfig config =
                new PipelineConfig(new ModelRef("yolo", "1"), 0.5, 5, 2, Set.of(), customRule, false, tracking);

        assertEquals(customRule, config.eventRule());
        assertFalse(config.detectionEnabled());
        assertEquals(tracking, config.tracking());
        assertTrue(config.labelDenyFilter().isEmpty(),
                "8-arg ctor chain (pre-CV-CLEAN-FEED-PLAN) defaults labelDenyFilter to empty");
        assertFalse(config.trace(), "8-arg ctor chain defaults trace off too");
    }

    @Test
    void tenArgCanonicalConstructorRoundTripsAnExplicitLabelDenyFilterAndTrace() {
        EventRuleConfig customRule = EventRuleConfig.defaults();
        TrackingConfig tracking = TrackingConfig.defaults();

        PipelineConfig config = new PipelineConfig(
                new ModelRef("yolo", "1"), 0.5, 5, 2, Set.of(), customRule, false, tracking, Set.of("bird"), true);

        assertEquals(Set.of("bird"), config.labelDenyFilter());
        assertTrue(config.trace(), "trace is a plain component of the canonical ctor like any other");
        // Wave T8 flipped defaults() to ASSOCIATE but the convenience ctors deliberately keep off():
        // "the component you did not mention keeps its pre-existing value" is a different contract
        // from "what a new stream should be".
        assertNotEquals(PipelineConfig.defaults().tracking(), TrackingConfig.off());
    }

    @Test
    void rejectsNullEventRule() {
        ModelRef model = new ModelRef("yolo", "1");

        assertThrows(IllegalArgumentException.class,
                () -> new PipelineConfig(model, 0.5, 5, 2, Set.of(), null, true, TrackingConfig.off()));
    }

    @Test
    void rejectsNullTracking() {
        ModelRef model = new ModelRef("yolo", "1");

        assertThrows(IllegalArgumentException.class, () -> new PipelineConfig(
                model, 0.5, 5, 2, Set.of(), EventRuleConfig.defaults(), true, null, Set.of(), false));
    }

    @Test
    void rejectsNullLabelDenyFilter() {
        ModelRef model = new ModelRef("yolo", "1");

        assertThrows(IllegalArgumentException.class, () -> new PipelineConfig(
                model, 0.5, 5, 2, Set.of(), EventRuleConfig.defaults(), true, TrackingConfig.off(), null, false));
    }

    @Test
    void labelFilterIsDefensivelyCopied() {
        Set<String> labels = new HashSet<>();
        labels.add("person");

        PipelineConfig config = new PipelineConfig(new ModelRef("yolo", "1"), 0.5, 5, 2, labels);

        labels.add("car");

        assertEquals(1, config.labelFilter().size());
        assertThrows(UnsupportedOperationException.class, () -> config.labelFilter().add("dog"));
    }

    @Test
    void labelDenyFilterIsDefensivelyCopied() {
        Set<String> denied = new HashSet<>();
        denied.add("tree");

        PipelineConfig config = new PipelineConfig(new ModelRef("yolo", "1"), 0.5, 5, 2, Set.of(),
                EventRuleConfig.defaults(), false, TrackingConfig.off(), denied, false);

        denied.add("cloud");

        assertEquals(1, config.labelDenyFilter().size());
        assertThrows(UnsupportedOperationException.class, () -> config.labelDenyFilter().add("bird"));
    }

    @Test
    void rejectsInvalidArguments() {
        ModelRef model = new ModelRef("yolo", "1");

        assertThrows(IllegalArgumentException.class, () -> new PipelineConfig(null, 0.5, 5, 2, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new PipelineConfig(model, -0.01, 5, 2, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new PipelineConfig(model, 1.01, 5, 2, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new PipelineConfig(model, 0.5, 0, 2, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new PipelineConfig(model, 0.5, 5, 0, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new PipelineConfig(model, 0.5, 5, 2, null));
    }

    @Test
    void twoDefaultsCallsAreEqualValues() {
        assertEquals(PipelineConfig.defaults(), PipelineConfig.defaults());
    }
}

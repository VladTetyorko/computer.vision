package com.drones.vision.perception.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventRuleConfigTest {

    @Test
    void defaultsMatchSpec() {
        EventRuleConfig defaults = EventRuleConfig.defaults();

        assertEquals(Set.of("person", "car"), defaults.labels());
        assertEquals(0.5, defaults.confidenceThreshold());
        assertEquals(3, defaults.consecutiveToOpen());
        assertEquals(Duration.ofSeconds(5), defaults.absenceToClose());
    }

    @Test
    void labelsAreDefensivelyCopied() {
        Set<String> labels = new HashSet<>();
        labels.add("person");

        EventRuleConfig config = new EventRuleConfig(labels, 0.5, 3, Duration.ofSeconds(5));

        labels.add("dog");

        assertEquals(1, config.labels().size());
        assertThrows(UnsupportedOperationException.class, () -> config.labels().add("cat"));
    }

    @Test
    void emptyLabelsIsValidAndMeansTrackNothing() {
        EventRuleConfig config = new EventRuleConfig(Set.of(), 0.5, 3, Duration.ofSeconds(5));

        assertTrue(config.labels().isEmpty());
    }

    @Test
    void rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> new EventRuleConfig(null, 0.5, 3, Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class,
                () -> new EventRuleConfig(Set.of("person"), -0.01, 3, Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class,
                () -> new EventRuleConfig(Set.of("person"), 1.01, 3, Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class,
                () -> new EventRuleConfig(Set.of("person"), 0.5, 0, Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class,
                () -> new EventRuleConfig(Set.of("person"), 0.5, -1, Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class,
                () -> new EventRuleConfig(Set.of("person"), 0.5, 3, null));
        assertThrows(IllegalArgumentException.class,
                () -> new EventRuleConfig(Set.of("person"), 0.5, 3, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new EventRuleConfig(Set.of("person"), 0.5, 3, Duration.ofSeconds(-1)));
    }

    @Test
    void twoDefaultsCallsAreEqualValues() {
        assertEquals(EventRuleConfig.defaults(), EventRuleConfig.defaults());
    }
}

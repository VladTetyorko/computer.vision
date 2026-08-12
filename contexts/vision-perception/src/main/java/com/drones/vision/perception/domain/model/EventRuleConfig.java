package com.drones.vision.perception.domain.model;

import java.time.Duration;
import java.util.Set;

/**
 * Debounce rule settings for {@link DetectionEvent}s (docs/plans/done/MVP2-PLAN.md §E, E-a): how many
 * consecutive qualifying {@link DetectionResult}s open an event, and how long an absence must
 * last before it closes.
 *
 * <p>Only labels in {@link #labels()} are tracked at all — a label outside this set never opens
 * an event, however confidently or often it is detected, matching the spec's own framing ("label
 * X ... opens an event") as naming a specific, curated watch-list rather than every label the
 * model can emit. An empty set is valid and simply means "track nothing" (the feature is
 * effectively off for a stream configured this way). {@link #confidenceThreshold()} and {@link
 * #consecutiveToOpen()} apply uniformly to every label in the set — there is no per-label
 * override (YAGNI until a real use case asks for one).
 *
 * @param labels               labels to track; a label not in this set is never debounced into an
 *                              event; defensively copied
 * @param confidenceThreshold  minimum confidence a detection must reach to count toward opening/
 *                              keeping an event open, range [0,1]
 * @param consecutiveToOpen    number of consecutive qualifying results required to open an event; must be positive
 * @param absenceToClose       how long a tracked label must be absent (or below threshold) before
 *                              an open event for it closes; must be positive
 */
public record EventRuleConfig(Set<String> labels, double confidenceThreshold, int consecutiveToOpen,
                               Duration absenceToClose) {

    public EventRuleConfig {
        if (labels == null) {
            throw new IllegalArgumentException("EventRuleConfig labels must not be null");
        }
        if (Double.isNaN(confidenceThreshold) || confidenceThreshold < 0.0 || confidenceThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "EventRuleConfig confidenceThreshold must be within [0,1]: " + confidenceThreshold);
        }
        if (consecutiveToOpen <= 0) {
            throw new IllegalArgumentException(
                    "EventRuleConfig consecutiveToOpen must be positive: " + consecutiveToOpen);
        }
        if (absenceToClose == null || absenceToClose.isNegative() || absenceToClose.isZero()) {
            throw new IllegalArgumentException("EventRuleConfig absenceToClose must be positive: " + absenceToClose);
        }
        labels = Set.copyOf(labels);
    }

    /**
     * Reasonable defaults (docs/plans/done/MVP2-PLAN.md §E, E-a): track {@code "person"}/{@code "car"} at a
     * 0.5 confidence threshold, 3 consecutive qualifying results to open, 5 seconds of absence to
     * close.
     *
     * @return a default {@code EventRuleConfig}
     */
    public static EventRuleConfig defaults() {
        return new EventRuleConfig(Set.of("person", "car"), 0.5, 3, Duration.ofSeconds(5));
    }
}

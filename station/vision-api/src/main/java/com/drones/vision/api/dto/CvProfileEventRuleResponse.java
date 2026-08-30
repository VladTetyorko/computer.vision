package com.drones.vision.api.dto;

import com.drones.vision.perception.domain.model.EventRuleConfig;

import java.time.Duration;
import java.util.List;

/**
 * The read-only {@code eventRule} object nested in a {@link CvProfileResponse}
 * (docs/plans/active/CV-SETTINGS-PLAN.md &sect;5.1) — present on every {@code GET} but never accepted
 * by {@link CvProfileRequest}: a profile's event rule is fixed at creation and start-time only
 * (&sect;5.1's own note — a running stream keeps whatever rule it started with, never re-armed by a
 * live edit; the only way to change it is to fork/create a new profile).
 *
 * @param labels                labels this profile's debounce rule tracks; empty means "track
 *                               nothing", matching {@link EventRuleConfig}'s own convention
 * @param confidenceThreshold   minimum confidence to count toward opening/keeping an event open,
 *                               [0,1]
 * @param consecutiveToOpen     consecutive qualifying detections required to open an event
 * @param absenceToCloseSeconds how long a tracked label must be absent before an open event
 *                               closes, in whole seconds — {@link EventRuleConfig#absenceToClose()}
 *                               converted from a {@link Duration}
 */
public record CvProfileEventRuleResponse(List<String> labels, double confidenceThreshold, int consecutiveToOpen,
                                          long absenceToCloseSeconds) {

    /**
     * Maps a profile's event rule to the wire.
     *
     * @param eventRule the rule to map
     * @return this shape's view of it
     */
    public static CvProfileEventRuleResponse from(EventRuleConfig eventRule) {
        return new CvProfileEventRuleResponse(List.copyOf(eventRule.labels()), eventRule.confidenceThreshold(),
                eventRule.consecutiveToOpen(), eventRule.absenceToClose().toSeconds());
    }
}

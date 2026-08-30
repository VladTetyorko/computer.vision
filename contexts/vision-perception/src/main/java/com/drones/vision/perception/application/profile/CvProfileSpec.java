package com.drones.vision.perception.application.profile;

import com.drones.vision.perception.domain.model.EventRuleConfig;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.perception.domain.model.TrackingConfig;

import java.util.List;
import java.util.Objects;

/**
 * What a caller supplies to {@link CvProfileService#create}/{@link CvProfileService#update} — every
 * {@link com.drones.vision.perception.domain.model.CvProfile} field the wire contract's {@code
 * POST}/{@code PUT /api/cv/profiles} accepts (docs/plans/active/CV-SETTINGS-PLAN.md &sect;5.1/&sect;5.2),
 * minus the fields the service itself owns: {@code id}, {@code builtIn} (always {@code false} for
 * anything a caller can create), {@code groupId} (a separate, explicit parameter — see {@link
 * CvProfileService#create}'s own javadoc for why it is not derived from the acting scope), and the
 * two timestamps.
 *
 * @param name                human-readable name; must not be blank
 * @param description         human-readable description; must not be {@code null}, may be blank
 * @param model               the CV model this profile runs
 * @param confidenceThreshold minimum confidence to keep a detection, range [0,1]
 * @param inferenceFps        target inference sample rate; must be positive
 * @param labelFilter         labels to keep; empty means all labels; defensively copied
 * @param labelDenyFilter     labels to drop even when {@code labelFilter} would keep them;
 *                            defensively copied
 * @param detectionEnabled    whether a stream started from this profile runs detection at all
 * @param tracking            tracking configuration a stream started from this profile uses
 * @param eventRule           debounce rule a stream started from this profile uses; start-time only
 */
public record CvProfileSpec(String name, String description, ModelRef model, double confidenceThreshold,
                             int inferenceFps, List<String> labelFilter, List<String> labelDenyFilter,
                             boolean detectionEnabled, TrackingConfig tracking, EventRuleConfig eventRule) {

    public CvProfileSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("CvProfileSpec name must not be blank");
        }
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(labelFilter, "labelFilter must not be null");
        Objects.requireNonNull(labelDenyFilter, "labelDenyFilter must not be null");
        Objects.requireNonNull(tracking, "tracking must not be null");
        Objects.requireNonNull(eventRule, "eventRule must not be null");
        labelFilter = List.copyOf(labelFilter);
        labelDenyFilter = List.copyOf(labelDenyFilter);
    }
}

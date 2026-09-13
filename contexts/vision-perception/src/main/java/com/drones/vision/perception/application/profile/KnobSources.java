package com.drones.vision.perception.application.profile;

import java.util.Objects;

/**
 * Per-knob provenance for one {@link EffectiveProfile} — which tier (or {@link
 * ProfileSource#INTENT}) actually supplied each of {@link com.drones.vision.perception.domain.model.CvProfile}'s
 * eight patchable knobs, at resolution time rather than only at save time (docs/plans/active/
 * CV-ORCHESTRATION-PLAN.md &sect;4.7/&sect;8 decision E22, wave W7). Every read of an effective
 * profile — not only the response to a create/update — can now say "this model came from your asset
 * profile, but that confidence threshold is still the organization's."
 *
 * <p><b>Deliberately {@code application.profile}, not {@code domain.model}</b> (which is where E22's
 * design text places it): every field here is a {@link ProfileSource}, and {@code ProfileSource}
 * itself lives in {@code application.profile} — a {@code domain.model} record naming it would violate
 * {@code ArchitectureTest}'s domain-purity rule the same way a domain {@link
 * com.drones.vision.perception.domain.model.CvProfile#foldOnto} calling {@code IntentPolicyResolver}
 * would (see that method's own javadoc for the parallel deviation). Moving {@code ProfileSource} into
 * {@code domain.model} instead was rejected for the same reason moving {@code IntentPolicyResolver}
 * was: it is imported by {@code station.vision-api} DTOs this wave already touches, but reclassifying
 * an enum that also names resolver-internal concepts as a domain type is a bigger, unscoped change for
 * a documentation-only relocation to force.
 *
 * <p>{@link #platform()} is the fold's starting point — every knob {@link ProfileSource#PLATFORM} —
 * before any bound tier has had a say; {@link CvProfileResolver#resolve} overwrites one field at a
 * time as each bound tier's profile (or its seeding intent) actually supplies that knob.
 *
 * @param model               which tier (or {@link ProfileSource#INTENT}) supplied {@code model}
 * @param confidenceThreshold which tier (or {@link ProfileSource#INTENT}) supplied {@code
 *                            confidenceThreshold}
 * @param inferenceFps        which tier (or {@link ProfileSource#INTENT}) supplied {@code
 *                            inferenceFps}
 * @param labelFilter         which tier (or {@link ProfileSource#INTENT}) supplied {@code
 *                            labelFilter}
 * @param labelDenyFilter     which tier supplied {@code labelDenyFilter} — never {@link
 *                            ProfileSource#INTENT}; no intent ever seeds this knob
 * @param detectionEnabled    which tier supplied {@code detectionEnabled} — never {@link
 *                            ProfileSource#INTENT}
 * @param tracking            which tier supplied the {@code tracking} group as a whole — never
 *                            {@link ProfileSource#INTENT}
 * @param eventRule           which tier supplied {@code eventRule} — never {@link
 *                            ProfileSource#INTENT}
 */
public record KnobSources(ProfileSource model, ProfileSource confidenceThreshold, ProfileSource inferenceFps,
                           ProfileSource labelFilter, ProfileSource labelDenyFilter, ProfileSource detectionEnabled,
                           ProfileSource tracking, ProfileSource eventRule) {

    public KnobSources {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(confidenceThreshold, "confidenceThreshold must not be null");
        Objects.requireNonNull(inferenceFps, "inferenceFps must not be null");
        Objects.requireNonNull(labelFilter, "labelFilter must not be null");
        Objects.requireNonNull(labelDenyFilter, "labelDenyFilter must not be null");
        Objects.requireNonNull(detectionEnabled, "detectionEnabled must not be null");
        Objects.requireNonNull(tracking, "tracking must not be null");
        Objects.requireNonNull(eventRule, "eventRule must not be null");
    }

    /**
     * The fold's floor: no bound tier has matched anything yet, so every knob is still attributed to
     * the platform default.
     *
     * @return a {@link KnobSources} with every field {@link ProfileSource#PLATFORM}
     */
    public static KnobSources platform() {
        return new KnobSources(ProfileSource.PLATFORM, ProfileSource.PLATFORM, ProfileSource.PLATFORM,
                ProfileSource.PLATFORM, ProfileSource.PLATFORM, ProfileSource.PLATFORM, ProfileSource.PLATFORM,
                ProfileSource.PLATFORM);
    }
}

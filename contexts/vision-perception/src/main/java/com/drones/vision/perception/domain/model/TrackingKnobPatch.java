package com.drones.vision.perception.domain.model;

/**
 * The tracking knobs one {@link CvProfile} may state — a partial, per-field statement over the five
 * {@link TrackingConfig} components a profile actually owns (docs/plans/active/CV-ORCHESTRATION-PLAN.md
 * &sect;4.7, wave W7, decision E22): {@code null} means <b>inherit this knob from the tier below</b>,
 * the same null-means-unchanged idiom {@link com.drones.vision.perception.application.stream.PipelineConfigPatch}/
 * {@link com.drones.vision.perception.application.stream.TrackingConfigPatch} already document for the
 * live PATCH path — applied here to the persisted profile fold instead ("unset", never "off"; CLAUDE.md
 * rule 10).
 *
 * <p><b>Deliberately not {@code TrackingConfigPatch} itself</b> (which is what E22's design literally
 * names): that type lives in {@code application.stream}, and {@link CvProfile} lives in {@code
 * domain.model} — {@code ArchitectureTest}'s domain-purity rule (a {@code domain}/{@code kernel}/{@code
 * platform} package may depend on nothing outside those plus {@code java.*}) forbids a domain record
 * from naming an application-layer type as a field. Moving {@code TrackingConfigPatch} into {@code
 * domain.model} instead was rejected: every one of its call sites, including {@code
 * StartStreamRequest}, would need a new import, and {@code StartStreamRequest#mergeOnto} is explicit,
 * off-limits stream-config-hot-path territory for this wave. This type is therefore a second, narrower
 * patch record — the five knobs {@code station.vision-api}'s own {@code CvProfileTrackingResponse}
 * already treats as "what a profile owns": {@link com.drones.vision.perception.application.stream.TrackingConfigPatch}'s
 * other five fields ({@code redetectIouPercent}, {@code maxAgeFrames}, {@code minHits}, {@code
 * reupdateMaxGapMillis}, {@code lock}) have no place here at all, rather than being validated
 * always-null the way E22's text asks for {@code lock} — a field that cannot exist needs no runtime
 * check to keep it absent.
 *
 * <p>A {@link CvProfile#tracking()} of {@code null} means "no tracking override at all — inherit the
 * whole group," matching {@link com.drones.vision.perception.application.stream.PipelineConfigPatch#tracking()}'s
 * own null semantics one level up; every field on a non-{@code null} instance is independently
 * nullable on top of that.
 *
 * @param mode              replacement tracking mode, or {@code null} to inherit
 * @param engineId          replacement tracker engine id, or {@code null} to inherit; {@code ""} is a
 *                          real value meaning "the server's default for the mode"
 * @param capabilityLevel   replacement capability-ladder ceiling, or {@code null} to inherit
 * @param verifyEveryMillis replacement {@code FOLLOW} re-verify cadence, or {@code null} to inherit
 * @param followFps         replacement Java-side sampler rate for {@code FOLLOW}, or {@code null} to
 *                          inherit
 */
public record TrackingKnobPatch(TrackingMode mode, String engineId, Integer capabilityLevel,
                                 Integer verifyEveryMillis, Integer followFps) {

    /** A patch that inherits every knob — the identity of {@link #foldOnto(TrackingConfig)}. */
    public static final TrackingKnobPatch NOTHING = new TrackingKnobPatch(null, null, null, null, null);

    /**
     * Folds this patch's present knobs onto {@code below}, leaving every absent one — including the
     * five {@link TrackingConfig} fields this type has no knob for at all — exactly as {@code below}
     * has it.
     *
     * @param below the configuration an absent/unowned field falls back to; never {@code null}
     * @return the folded configuration
     * @throws IllegalArgumentException if {@code below} is {@code null}, or a folded value fails
     *                                   {@link TrackingConfig}'s own validation
     */
    public TrackingConfig foldOnto(TrackingConfig below) {
        if (below == null) {
            throw new IllegalArgumentException("TrackingKnobPatch foldOnto below must not be null");
        }
        return new TrackingConfig(
                mode == null ? below.mode() : mode,
                engineId == null ? below.engineId() : engineId,
                verifyEveryMillis == null ? below.verifyEveryMillis() : verifyEveryMillis,
                followFps == null ? below.followFps() : followFps,
                below.redetectIouPercent(),
                below.maxAgeFrames(),
                below.minHits(),
                capabilityLevel == null ? below.capabilityLevel() : capabilityLevel,
                below.reupdateMaxGapMillis(),
                below.lock());
    }
}

package com.drones.vision.api.dto;

import com.drones.vision.perception.application.profile.KnobSources;

/**
 * The {@code sources} object nested in {@link EffectiveCvProfileResponse} (docs/plans/active/
 * CV-ORCHESTRATION-PLAN.md &sect;4.7/&sect;8, decision E22, wave W7.3) — the wire view of {@link
 * KnobSources}: which tier (or {@code INTENT}) actually supplied each of the eight knobs the asset
 * &rarr; category &rarr; organization &rarr; platform fold produced. Unlike {@link
 * CvProfileResponse.Sources} (four knobs, computed from one persisted profile's own fields in
 * isolation), this reports the FOLD's real per-knob provenance across every bound tier — "this
 * model came from your asset profile, but that confidence threshold is still the organization's."
 *
 * <p>Every field is always present on the wire (no {@code @JsonInclude(NON_NULL)} here, unlike this
 * DTO package's other {@code Sources}/patch shapes): {@link KnobSources}' own compact constructor
 * guarantees every field is non-{@code null} — {@code PLATFORM} is the fold's own floor, never an
 * absent value.
 *
 * @param model               {@code "ASSET"}/{@code "CATEGORY"}/{@code "ORGANIZATION"}/{@code
 *                            "PLATFORM"}/{@code "INTENT"} — which tier (or intent) supplied {@code
 *                            model}
 * @param confidenceThreshold which tier (or intent) supplied {@code confidenceThreshold}
 * @param inferenceFps        which tier (or intent) supplied {@code inferenceFps}
 * @param labelFilter         which tier (or intent) supplied {@code labelFilter}
 * @param labelDenyFilter     which tier supplied {@code labelDenyFilter} — never {@code "INTENT"}
 * @param detectionEnabled    which tier supplied {@code detectionEnabled} — never {@code "INTENT"}
 * @param tracking            which tier supplied the {@code tracking} group as a whole — never
 *                            {@code "INTENT"}
 * @param eventRule           which tier supplied {@code eventRule} — never {@code "INTENT"}
 */
public record CvKnobSourcesResponse(String model, String confidenceThreshold, String inferenceFps,
                                     String labelFilter, String labelDenyFilter, String detectionEnabled,
                                     String tracking, String eventRule) {

    /**
     * Maps a fold's per-knob provenance to the wire.
     *
     * @param sources the fold's own {@link KnobSources}
     * @return this shape's view of it
     */
    public static CvKnobSourcesResponse from(KnobSources sources) {
        return new CvKnobSourcesResponse(sources.model().name(), sources.confidenceThreshold().name(),
                sources.inferenceFps().name(), sources.labelFilter().name(), sources.labelDenyFilter().name(),
                sources.detectionEnabled().name(), sources.tracking().name(), sources.eventRule().name());
    }
}

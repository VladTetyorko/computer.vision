package com.drones.vision.api.dto;

/**
 * Response body for {@code GET /api/cv/profiles/effective?assetId=…}
 * (docs/plans/active/CV-SETTINGS-PLAN.md &sect;5.2's frozen wire contract) — the profile that would
 * actually apply to this asset's next stream start, plus which layer of the asset &rarr; category
 * &rarr; organization &rarr; platform fold produced it.
 *
 * <p>{@link com.drones.vision.api.controller.CvProfileController} builds {@link #profile()} from the
 * {@code com.drones.vision.perception.application.profile.EffectiveProfile} {@code
 * CvProfileService#effective} returns: {@link CvProfileResponse#platformDefault} when {@code source}
 * is {@code "PLATFORM"} (no binding matched at any level), or {@link
 * CvProfileResponse#fromEffective} otherwise — every knob taken from the fold's own resolved
 * configuration, never the matched tier's raw (possibly partial) profile fields, so a knob that tier
 * left unset (inherited) still shows its real, effective value here.
 *
 * <h2>Wave W7.3 — sources and intent (docs/plans/active/CV-ORCHESTRATION-PLAN.md &sect;4.7/&sect;8,
 * decision E22)</h2>
 * {@link #sources()} and {@link #intent()} are new: the fold now reports, for every one of the eight
 * patchable knobs, which tier (or {@code "INTENT"}) actually supplied it — not only {@code model}/
 * {@code labelFilter}, and not only on a create/update response. {@code intent} is the persisted
 * {@link com.drones.vision.perception.domain.model.Intent} of the most specific tier that matched
 * (the same one {@link #source()} names), or absent when that tier set none.
 *
 * @param assetId the asset this resolution is for, as a canonical UUID string
 * @param profile the profile that would apply
 * @param source  {@code "ASSET"}/{@code "CATEGORY"}/{@code "ORGANIZATION"}/{@code "PLATFORM"} —
 *                {@code ProfileSource#name()} verbatim, which is byte-identical to {@code
 *                BindingScope#name()} for the three non-platform values
 * @param sources per-knob provenance across every bound tier, all eight knobs — see {@link
 *                CvKnobSourcesResponse}'s own javadoc
 * @param intent  the matched tier's own persisted intent pick, or absent for none
 */
public record EffectiveCvProfileResponse(String assetId, CvProfileResponse profile, String source,
                                          CvKnobSourcesResponse sources, String intent) {
}

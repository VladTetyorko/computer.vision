package com.drones.vision.api.dto;

/**
 * Response body for {@code GET /api/cv/profiles/effective?assetId=…}
 * (docs/plans/active/CV-SETTINGS-PLAN.md &sect;5.2's frozen wire contract) — the profile that would
 * actually apply to this asset's next stream start, plus which layer of the asset &rarr; category
 * &rarr; organization &rarr; platform fold produced it.
 *
 * <p>{@link CvProfileController} builds {@link #profile()} from the {@code
 * com.drones.vision.perception.application.profile.EffectiveProfile} {@code
 * CvProfileService#effective} returns: {@link CvProfileResponse#platformDefault} when {@code source}
 * is {@code "PLATFORM"} (no binding matched at any level), or the real, freshly-fetched {@link
 * com.drones.vision.perception.domain.model.CvProfile} otherwise — {@code EffectiveProfile} itself
 * carries only the matched profile's id/name, not its full field set.
 *
 * @param assetId the asset this resolution is for, as a canonical UUID string
 * @param profile the profile that would apply
 * @param source  {@code "ASSET"}/{@code "CATEGORY"}/{@code "ORGANIZATION"}/{@code "PLATFORM"} —
 *                {@code ProfileSource#name()} verbatim, which is byte-identical to {@code
 *                BindingScope#name()} for the three non-platform values
 */
public record EffectiveCvProfileResponse(String assetId, CvProfileResponse profile, String source) {
}

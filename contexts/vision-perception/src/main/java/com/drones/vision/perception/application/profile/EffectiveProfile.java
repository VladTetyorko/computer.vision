package com.drones.vision.perception.application.profile;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.perception.domain.model.CvProfileId;
import com.drones.vision.perception.domain.model.PipelineConfig;

import java.util.Objects;

/**
 * The one {@link PipelineConfig} one asset resolves to right now, and which layer of
 * docs/plans/active/CV-SETTINGS-PLAN.md &sect;3.1's asset &rarr; category &rarr; organization &rarr;
 * platform fold supplied it — both {@link CvProfileResolver}'s return value and the read model
 * behind {@code GET /api/cv/profiles/effective} (docs/plans/active/CV-SETTINGS-PLAN.md &sect;5.2).
 *
 * <p>{@code profileId}/{@code profileName} are {@code null} exactly when {@code source} is {@link
 * ProfileSource#PLATFORM} — no binding matched at any level, so there is no profile to name; every
 * other source names the profile that matched.
 *
 * @param assetId     the asset this resolution is for
 * @param profileId   the matched profile's id, or {@code null} iff {@code source} is {@link
 *                    ProfileSource#PLATFORM}
 * @param profileName the matched profile's name, or {@code null} iff {@code source} is {@link
 *                    ProfileSource#PLATFORM}
 * @param source      which layer matched
 * @param config      the resolved {@link PipelineConfig} — the matched profile folded over the
 *                    caller's platform default, or that platform default unchanged for {@link
 *                    ProfileSource#PLATFORM}
 */
public record EffectiveProfile(AssetId assetId, CvProfileId profileId, String profileName, ProfileSource source,
                                PipelineConfig config) {

    public EffectiveProfile {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(config, "config must not be null");
        boolean namesAProfile = profileId != null || profileName != null;
        if (source == ProfileSource.PLATFORM && namesAProfile) {
            throw new IllegalArgumentException("EffectiveProfile profileId/profileName must be null for PLATFORM");
        }
        if (source != ProfileSource.PLATFORM && (profileId == null || profileName == null)) {
            throw new IllegalArgumentException(
                    "EffectiveProfile profileId/profileName must not be null when source is not PLATFORM");
        }
    }
}

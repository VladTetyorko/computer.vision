package com.drones.vision.perception.application.profile;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.perception.domain.model.BindingScope;
import com.drones.vision.perception.domain.model.CvProfile;
import com.drones.vision.perception.domain.model.PipelineConfig;

import java.util.Objects;
import java.util.Optional;

/**
 * The asset &rarr; category &rarr; organization &rarr; platform fold
 * (docs/plans/active/CV-SETTINGS-PLAN.md &sect;3.1 rule 1): the first {@link
 * com.drones.vision.perception.domain.model.CvProfileBinding} found, checked in that order, wins;
 * no binding at any level means the caller's platform default applies unchanged.
 *
 * <p>Resolution happens once, at the moment this is called — {@link
 * com.drones.vision.perception.application.stream.DefaultStreamService#start} calls this exactly
 * once per stream start and never again for that stream's life, matching the plan's "resolution
 * happens once, at start; nothing re-resolves mid-flight."
 *
 * <h2>Threading</h2>
 * Holds no mutable state of its own — every call reads a fresh {@link CvProfileCache.Snapshot}.
 */
public final class CvProfileResolver {

    private final CvProfileCache cache;

    public CvProfileResolver(CvProfileCache cache) {
        this.cache = Objects.requireNonNull(cache, "cache must not be null");
    }

    /**
     * Resolves one asset's effective CV configuration.
     *
     * @param assetId       the asset to resolve for
     * @param categoryId    the asset's category
     * @param groupId       the asset's owning group
     * @param platformDefault the configuration to fall back to when no binding matches at any
     *                        level, and the source of {@link PipelineConfig#maxInFlightInferences()}
     *                        even when a profile does match (host capacity, never a profile
     *                        concern — see {@link CvProfile#toPipelineConfig(PipelineConfig)})
     * @return the resolved profile (if any), its source, and the folded {@link PipelineConfig}
     */
    public EffectiveProfile resolve(AssetId assetId, CategoryId categoryId, GroupId groupId,
                                     PipelineConfig platformDefault) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(categoryId, "categoryId must not be null");
        Objects.requireNonNull(groupId, "groupId must not be null");
        Objects.requireNonNull(platformDefault, "platformDefault must not be null");

        CvProfileCache.Snapshot snapshot = cache.snapshot();
        Optional<Match> match = matchAt(snapshot, BindingScope.ASSET, assetId.value().toString(), ProfileSource.ASSET)
                .or(() -> matchAt(snapshot, BindingScope.CATEGORY, categoryId.slug(), ProfileSource.CATEGORY))
                .or(() -> matchAt(snapshot, BindingScope.ORGANIZATION, groupId.value().toString(),
                        ProfileSource.ORGANIZATION));

        if (match.isEmpty()) {
            return new EffectiveProfile(assetId, null, null, ProfileSource.PLATFORM, platformDefault);
        }
        CvProfile profile = match.get().profile();
        return new EffectiveProfile(assetId, profile.id(), profile.name(), match.get().source(),
                profile.toPipelineConfig(platformDefault));
    }

    private static Optional<Match> matchAt(CvProfileCache.Snapshot snapshot, BindingScope scopeKind, String scopeId,
                                            ProfileSource source) {
        return snapshot.findBinding(scopeKind, scopeId)
                .flatMap(binding -> snapshot.findById(binding.profileId()))
                .map(profile -> new Match(profile, source));
    }

    private record Match(CvProfile profile, ProfileSource source) {
    }
}

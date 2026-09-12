package com.drones.vision.perception.application.profile;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.perception.domain.model.BindingScope;
import com.drones.vision.perception.domain.model.CvProfile;
import com.drones.vision.perception.domain.model.PipelineConfig;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The platform &rarr; organization &rarr; category &rarr; asset fold (docs/plans/active/
 * CV-SETTINGS-PLAN.md &sect;3.1 rule 1, widened to a genuine tier-by-tier fold by
 * docs/plans/active/CV-ORCHESTRATION-PLAN.md &sect;4.7/&sect;4.4, wave W2.6): every {@link
 * com.drones.vision.perception.domain.model.CvProfileBinding} bound at ANY of the three levels is
 * applied, in order from least to most specific — organization, then category, then asset — each
 * one's {@link CvProfile#toPipelineConfig(PipelineConfig)} completely superseding whatever the
 * previous tier produced. No binding at any level means {@code platformDefault} applies unchanged;
 * {@link #resolve}'s reported {@link EffectiveProfile#profileId()}/{@link
 * EffectiveProfile#source()} always name the <em>most specific</em> bound tier, exactly as before
 * this wave.
 *
 * <p><b>Why this is a genuine behavior change even though every {@link CvProfile} is a complete
 * record (no field is ever partial):</b> before W2.6, a bound asset-tier profile made the resolver
 * <em>stop looking entirely</em> — a simultaneously-bound category or organization profile was
 * never consulted at all, not even in principle. After W2.6, the resolver genuinely folds every
 * bound tier in sequence; for two {@link CvProfile}s (which specify every field) the observable
 * {@link PipelineConfig} is unchanged from before (the most specific tier's fields always won
 * either way), but the fold now has the shape the plan asks for, and a future profile type that is
 * only <em>partially</em> specified (e.g. an intent-only, platform-tier seed —
 * &sect;4.7/{@link IntentPolicyResolver}) would compose correctly through this same fold without
 * another rewrite of this class. The session tier (an explicit {@code StartStreamRequest} field
 * beating whatever this method resolved) already patches on top one level up — {@code
 * StreamDetectionSupport#resolveStartConfig}'s own javadoc.
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
     * @param platformDefault the seed every tier below folds over, and the source of {@link
     *                        PipelineConfig#maxInFlightInferences()}/{@link
     *                        PipelineConfig#trace()} regardless of which (if any) tier matched
     *                        (host capacity and per-session inspector demand are never a profile
     *                        concern — see {@link CvProfile#toPipelineConfig(PipelineConfig)})
     * @return the resolved profile (if any), its most-specific-bound source, and the folded
     *         {@link PipelineConfig}
     */
    public EffectiveProfile resolve(AssetId assetId, CategoryId categoryId, GroupId groupId,
                                     PipelineConfig platformDefault) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(categoryId, "categoryId must not be null");
        Objects.requireNonNull(groupId, "groupId must not be null");
        Objects.requireNonNull(platformDefault, "platformDefault must not be null");

        CvProfileCache.Snapshot snapshot = cache.snapshot();
        PipelineConfig folded = platformDefault;
        Match mostSpecific = null;
        for (Match candidate : List.of(
                matchAt(snapshot, BindingScope.ORGANIZATION, groupId.value().toString(), ProfileSource.ORGANIZATION),
                matchAt(snapshot, BindingScope.CATEGORY, categoryId.slug(), ProfileSource.CATEGORY),
                matchAt(snapshot, BindingScope.ASSET, assetId.value().toString(), ProfileSource.ASSET))
                .stream().flatMap(Optional::stream).toList()) {
            // Deliberately reassigns rather than accumulates a diff -- see this class's own javadoc:
            // every CvProfile fully specifies every field, so "folding" one tier over another means
            // the more specific tier's complete config simply supersedes the less specific one's.
            folded = candidate.profile().toPipelineConfig(platformDefault);
            mostSpecific = candidate;
        }

        if (mostSpecific == null) {
            return new EffectiveProfile(assetId, null, null, ProfileSource.PLATFORM, platformDefault);
        }
        return new EffectiveProfile(assetId, mostSpecific.profile().id(), mostSpecific.profile().name(),
                mostSpecific.source(), folded);
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

package com.drones.vision.perception.application.profile;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.perception.domain.model.BindingScope;
import com.drones.vision.perception.domain.model.CvProfile;
import com.drones.vision.perception.domain.model.Intent;
import com.drones.vision.perception.domain.model.PipelineConfig;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The platform &rarr; organization &rarr; category &rarr; asset fold (docs/plans/active/
 * CV-SETTINGS-PLAN.md &sect;3.1 rule 1), genuinely per-knob as of wave W7.1
 * (docs/plans/active/CV-ORCHESTRATION-PLAN.md &sect;4.7, decision E22 — "a profile is a patch"):
 * every {@link com.drones.vision.perception.domain.model.CvProfileBinding} bound at ANY of the
 * three levels is applied, in order from least to most specific — organization, then category,
 * then asset — each one's {@link CvProfile#foldOnto(PipelineConfig)} patching only the knobs that
 * tier's profile actually sets, over the running accumulation from every tier before it. No
 * binding at any level means {@code platformDefault} applies unchanged (same instance); {@link
 * #resolve}'s reported {@link EffectiveProfile#profileId()}/{@link EffectiveProfile#source()}
 * still name the <em>most specific</em> bound tier, unchanged since wave W2.6 — the new
 * per-<em>knob</em> provenance lives in {@link EffectiveProfile#sources()} instead.
 *
 * <h2>Intent seeding</h2>
 * A tier's own {@link CvProfile#intent()}, when non-null, is resolved via {@link
 * IntentPolicyResolver#resolve} <em>before</em> that tier's own explicit knobs are folded on top —
 * matching {@link CvProfile#foldOnto(PipelineConfig)}'s own documented seed contract, but performed
 * here rather than inside that (deliberately intent-agnostic, domain-layer) method: the resolved
 * {@link IntentPolicy} patches {@code model}/{@code labelFilter}/{@code confidenceThreshold}/{@code
 * inferenceFps} onto the running accumulation, forming the seed this tier's profile is then folded
 * onto via its own {@code foldOnto}. A more specific tier's own {@code intent} always re-seeds these
 * four knobs afresh from that tier's own {@link IntentPolicy}, superseding whatever a less specific
 * tier's intent (or explicit value) had put there — exactly like any other knob a more specific tier
 * chooses to set.
 *
 * <h2>Per-knob provenance</h2>
 * {@link EffectiveProfile#sources()} starts at {@link KnobSources#platform()} and is updated once
 * per bound tier: a knob this tier's profile sets explicitly (non-null) is attributed to that tier;
 * a knob left null but seeded by that tier's own non-null {@code intent} — only the four knobs
 * {@link IntentPolicyResolver} actually seeds: {@code model}/{@code confidenceThreshold}/{@code
 * inferenceFps}/{@code labelFilter} — is attributed to {@link ProfileSource#INTENT}; every other
 * knob (or every knob, if this tier's profile is unbound) simply carries forward its provenance from
 * the tier before it. {@link EffectiveProfile#intent()} tracks the same way: it is overwritten by
 * every bound tier's own non-null {@code intent} in turn, so it ends on the most specific tier that
 * set one (or {@code null} if none did).
 *
 * <p>The session tier (an explicit {@code StartStreamRequest} field beating whatever this method
 * resolved) already patches on top one level up — {@code
 * StreamDetectionSupport#resolveStartConfig}'s own javadoc — and is out of this resolver's scope.
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
     *                        concern — see {@link CvProfile#foldOnto(PipelineConfig)})
     * @return the resolved profile (if any), its most-specific-bound source, the folded {@link
     *         PipelineConfig}, per-knob provenance, and the most specific seeding {@link Intent}
     */
    public EffectiveProfile resolve(AssetId assetId, CategoryId categoryId, GroupId groupId,
                                     PipelineConfig platformDefault) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(categoryId, "categoryId must not be null");
        Objects.requireNonNull(groupId, "groupId must not be null");
        Objects.requireNonNull(platformDefault, "platformDefault must not be null");

        CvProfileCache.Snapshot snapshot = cache.snapshot();
        PipelineConfig folded = platformDefault;
        KnobSources sources = KnobSources.platform();
        Intent intent = null;
        Match mostSpecific = null;
        for (Match candidate : List.of(
                matchAt(snapshot, BindingScope.ORGANIZATION, groupId.value().toString(), ProfileSource.ORGANIZATION),
                matchAt(snapshot, BindingScope.CATEGORY, categoryId.slug(), ProfileSource.CATEGORY),
                matchAt(snapshot, BindingScope.ASSET, assetId.value().toString(), ProfileSource.ASSET))
                .stream().flatMap(Optional::stream).toList()) {
            CvProfile profile = candidate.profile();
            ProfileSource tier = candidate.source();

            PipelineConfig seed = profile.intent() == null ? folded
                    : seedWithIntent(folded, profile.intent(), profile.labelFilter());
            folded = profile.foldOnto(seed);
            sources = accumulate(sources, profile, tier);
            if (profile.intent() != null) {
                intent = profile.intent();
            }
            mostSpecific = candidate;
        }

        if (mostSpecific == null) {
            return new EffectiveProfile(assetId, null, null, ProfileSource.PLATFORM, platformDefault,
                    KnobSources.platform(), null);
        }
        return new EffectiveProfile(assetId, mostSpecific.profile().id(), mostSpecific.profile().name(),
                mostSpecific.source(), folded, sources, intent);
    }

    /**
     * The intent-seed step {@link CvProfile#foldOnto(PipelineConfig)}'s own javadoc documents but
     * deliberately does not perform itself (that method is intent-agnostic — see its javadoc for
     * why): patches {@code below} with the {@link IntentPolicy} {@code intent} resolves to, before
     * the profile's own explicit knobs fold on top of the result. Every other {@link PipelineConfig}
     * field — including {@code maxInFlightInferences}/{@code trace}, never a profile or intent
     * concern — passes through {@code below} unchanged.
     */
    private static PipelineConfig seedWithIntent(PipelineConfig below, Intent intent, List<String> labelFilter) {
        IntentPolicy policy = IntentPolicyResolver.resolve(intent, labelFilter);
        return new PipelineConfig(policy.model(), policy.reportThreshold(), policy.rateCeiling(),
                below.maxInFlightInferences(), policy.classSet(), below.eventRule(), below.detectionEnabled(),
                below.tracking(), below.labelDenyFilter(), below.trace());
    }

    /**
     * One tier's contribution to per-knob provenance: a knob this tier's profile sets explicitly
     * wins outright; a knob this tier leaves null but this tier's own {@code intent} seeds (only the
     * four {@link IntentPolicyResolver} actually resolves) is attributed to {@link
     * ProfileSource#INTENT}; every other knob carries {@code below}'s provenance forward untouched.
     */
    private static KnobSources accumulate(KnobSources below, CvProfile profile, ProfileSource tier) {
        boolean intentSeeded = profile.intent() != null;
        return new KnobSources(
                profile.model() != null ? tier : intentSeeded ? ProfileSource.INTENT : below.model(),
                profile.confidenceThreshold() != null ? tier
                        : intentSeeded ? ProfileSource.INTENT : below.confidenceThreshold(),
                profile.inferenceFps() != null ? tier : intentSeeded ? ProfileSource.INTENT : below.inferenceFps(),
                profile.labelFilter() != null ? tier : intentSeeded ? ProfileSource.INTENT : below.labelFilter(),
                profile.labelDenyFilter() != null ? tier : below.labelDenyFilter(),
                profile.detectionEnabled() != null ? tier : below.detectionEnabled(),
                profile.tracking() != null ? tier : below.tracking(),
                profile.eventRule() != null ? tier : below.eventRule());
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

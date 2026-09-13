package com.drones.vision.perception.application.profile;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.perception.domain.model.BindingScope;
import com.drones.vision.perception.domain.model.CvProfile;
import com.drones.vision.perception.domain.model.CvProfileBinding;
import com.drones.vision.perception.domain.model.CvProfileId;
import com.drones.vision.perception.domain.model.EventRuleConfig;
import com.drones.vision.perception.domain.model.Intent;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.perception.domain.model.TrackingConfig;
import com.drones.vision.perception.domain.model.TrackingKnobPatch;
import com.drones.vision.perception.domain.model.TrackingMode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CvProfileResolver}: the asset &rarr; category &rarr; organization &rarr; platform fold
 * (docs/plans/active/CV-SETTINGS-PLAN.md &sect;3.1).
 */
class CvProfileResolverTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static CvProfile profile(String name, GroupId groupId, String modelId) {
        return new CvProfile(CvProfileId.random(), name, "", false, groupId, new ModelRef(modelId, "latest"), 0.4,
                10, List.of(), List.of(), true, null, EventRuleConfig.defaults(), null, NOW, NOW);
    }

    /** Like {@link #profile(String, GroupId, String)}, but with a caller-chosen {@code
     *  confidenceThreshold}/{@code inferenceFps}/{@code labelFilter} -- lets a test build a profile
     *  whose fields came from a mix of sources (e.g. an intent-seeded model/labelFilter alongside an
     *  operator's own explicit confidence/fps) without pretending {@link CvProfile} itself has any
     *  memory of that mix (it doesn't -- every field is just a value once persisted). */
    private static CvProfile profile(String name, GroupId groupId, ModelRef model, double confidenceThreshold,
                                      int inferenceFps, List<String> labelFilter) {
        return new CvProfile(CvProfileId.random(), name, "", false, groupId, model, confidenceThreshold,
                inferenceFps, labelFilter, List.of(), true, null, EventRuleConfig.defaults(), null, NOW,
                NOW);
    }

    private static CvProfileResolver resolverWith(CvProfile profile, CvProfileBinding binding) {
        InMemoryCvProfileRepositoryPort repository = new InMemoryCvProfileRepositoryPort();
        repository.save(profile);
        repository.saveBinding(binding);
        return new CvProfileResolver(new CvProfileCache(repository, new CvProfileCacheSettings(Duration.ofMinutes(5))));
    }

    @Test
    void noBindingAtAnyLevelReturnsThePlatformDefaultUnchanged() {
        CvProfileResolver resolver =
                new CvProfileResolver(new CvProfileCache(new InMemoryCvProfileRepositoryPort(),
                        new CvProfileCacheSettings(Duration.ofMinutes(5))));
        PipelineConfig platformDefault = PipelineConfig.defaults();

        EffectiveProfile resolved =
                resolver.resolve(AssetId.random(), new CategoryId("quadcopter"), GroupId.random(), platformDefault);

        assertEquals(ProfileSource.PLATFORM, resolved.source());
        assertNull(resolved.profileId());
        assertNull(resolved.profileName());
        assertSame(platformDefault, resolved.config(), "unbound must fold to the exact same PipelineConfig instance");
        assertEquals(KnobSources.platform(), resolved.sources(), "no tier bound means every knob reads PLATFORM");
        assertNull(resolved.intent(), "no tier bound means no intent seeded anything");
    }

    @Test
    void assetBindingWinsOverCategoryAndOrganization() {
        AssetId assetId = AssetId.random();
        CategoryId categoryId = new CategoryId("quadcopter");
        GroupId groupId = GroupId.random();

        InMemoryCvProfileRepositoryPort repository = new InMemoryCvProfileRepositoryPort();
        CvProfile assetProfile = profile("mast-cams", groupId, "yolo26n.pt");
        CvProfile categoryProfile = profile("people-vehicles", groupId, "yolo26n.pt");
        CvProfile orgProfile = profile("video-only", groupId, "yolo26n.pt");
        repository.save(assetProfile);
        repository.save(categoryProfile);
        repository.save(orgProfile);
        repository.saveBinding(new CvProfileBinding(BindingScope.ASSET, assetId.value().toString(), assetProfile.id(), NOW));
        repository.saveBinding(new CvProfileBinding(BindingScope.CATEGORY, categoryId.slug(), categoryProfile.id(), NOW));
        repository.saveBinding(new CvProfileBinding(BindingScope.ORGANIZATION, groupId.value().toString(), orgProfile.id(), NOW));
        CvProfileResolver resolver =
                new CvProfileResolver(new CvProfileCache(repository, new CvProfileCacheSettings(Duration.ofMinutes(5))));

        EffectiveProfile resolved = resolver.resolve(assetId, categoryId, groupId, PipelineConfig.defaults());

        assertEquals(ProfileSource.ASSET, resolved.source());
        assertEquals(assetProfile.id(), resolved.profileId());
    }

    @Test
    void categoryBindingWinsOverOrganizationWhenNoAssetBinding() {
        AssetId assetId = AssetId.random();
        CategoryId categoryId = new CategoryId("quadcopter");
        GroupId groupId = GroupId.random();

        InMemoryCvProfileRepositoryPort repository = new InMemoryCvProfileRepositoryPort();
        CvProfile categoryProfile = profile("people-vehicles", groupId, "yolo26n.pt");
        CvProfile orgProfile = profile("video-only", groupId, "yolo26n.pt");
        repository.save(categoryProfile);
        repository.save(orgProfile);
        repository.saveBinding(new CvProfileBinding(BindingScope.CATEGORY, categoryId.slug(), categoryProfile.id(), NOW));
        repository.saveBinding(new CvProfileBinding(BindingScope.ORGANIZATION, groupId.value().toString(), orgProfile.id(), NOW));
        CvProfileResolver resolver =
                new CvProfileResolver(new CvProfileCache(repository, new CvProfileCacheSettings(Duration.ofMinutes(5))));

        EffectiveProfile resolved = resolver.resolve(assetId, categoryId, groupId, PipelineConfig.defaults());

        assertEquals(ProfileSource.CATEGORY, resolved.source());
        assertEquals(categoryProfile.id(), resolved.profileId());
    }

    @Test
    void organizationBindingAppliesWhenNoAssetOrCategoryBinding() {
        GroupId groupId = GroupId.random();
        CvProfile orgProfile = profile("video-only", groupId, "yolo26n.pt");
        CvProfileResolver resolver = resolverWith(orgProfile,
                new CvProfileBinding(BindingScope.ORGANIZATION, groupId.value().toString(), orgProfile.id(), NOW));

        EffectiveProfile resolved =
                resolver.resolve(AssetId.random(), new CategoryId("rover"), groupId, PipelineConfig.defaults());

        assertEquals(ProfileSource.ORGANIZATION, resolved.source());
        assertEquals(orgProfile.id(), resolved.profileId());
    }

    @Test
    void maxInFlightInferencesAndTraceAlwaysComeFromThePlatformDefaultEvenWhenAProfileMatches() {
        AssetId assetId = AssetId.random();
        GroupId groupId = GroupId.random();
        CvProfile matched = profile("mast-cams", groupId, "yolo26n.pt");
        CvProfileResolver resolver = resolverWith(matched,
                new CvProfileBinding(BindingScope.ASSET, assetId.value().toString(), matched.id(), NOW));
        PipelineConfig platformDefault = new PipelineConfig(new ModelRef("other", "v1"), 0.9, 30, 17, Set.of(),
                EventRuleConfig.defaults(), false, TrackingConfig.off(), Set.of(), true);

        EffectiveProfile resolved = resolver.resolve(assetId, new CategoryId("fixed-camera"), groupId, platformDefault);

        assertEquals(17, resolved.config().maxInFlightInferences(), "host capacity is never a profile concern");
        assertTrue(resolved.config().trace(),
                "trace is per-session inspector demand (CV-ORCHESTRATION-PLAN.md §4.4), never a profile concern");
        assertEquals(matched.model(), resolved.config().model(), "every other field comes from the matched profile");
    }

    /**
     * Patch-over-seed, tier 1: an asset-tier profile whose {@code model}/{@code labelFilter} came
     * from {@link IntentPolicyResolver} (the platform-tier seed, docs/plans/active/
     * CV-ORCHESTRATION-PLAN.md &sect;4.7) at the moment it was created, alongside its own explicit
     * {@code confidenceThreshold}/{@code inferenceFps} an operator typed by hand, coexist correctly
     * in what {@link CvProfileResolver#resolve} produces -- and the whole bundle wholesale-supersedes
     * a simultaneously-bound category profile, exactly as an asset profile with no intent history at
     * all would. This is the resolver's own actual "patch-over-seed" shape: {@link
     * IntentPolicyResolver} patches the *platform* tier once, at profile-creation time (see {@code
     * station/vision-api}'s {@code CvProfileRequest#toSpec()}); {@link CvProfileResolver} then folds
     * that already-composed profile over the next tier down exactly like any other profile -- it has
     * no notion of "this profile was partly intent-seeded" and does not need one for the fold itself
     * to be correct.
     */
    @Test
    void assetTierProfileBuiltFromIntentKeepsBothTheIntentSeededAndTheExplicitKnobsWhenItSupersedesCategory() {
        AssetId assetId = AssetId.random();
        CategoryId categoryId = new CategoryId("quadcopter");
        GroupId groupId = GroupId.random();

        // Simulates CvProfileRequest#toSpec() seeding model/labelFilter from Intent.PEOPLE while the
        // operator's own explicit confidenceThreshold (0.55, deliberately not PEOPLE's own 0.40
        // detectFloor) and inferenceFps (12) are taken verbatim -- the same "some knobs from intent,
        // some knobs explicit" mix toSpec() itself produces, tested directly at the DTO level by
        // CvProfileRequestTest.
        IntentPolicy peoplePolicy = IntentPolicyResolver.resolve(Intent.PEOPLE, List.of());
        CvProfile assetProfile =
                profile("mast-cams", groupId, peoplePolicy.model(), 0.55, 12, List.copyOf(peoplePolicy.classSet()));
        CvProfile categoryProfile = profile("people-vehicles", groupId, "yolo26n.pt");

        InMemoryCvProfileRepositoryPort repository = new InMemoryCvProfileRepositoryPort();
        repository.save(assetProfile);
        repository.save(categoryProfile);
        repository.saveBinding(new CvProfileBinding(BindingScope.ASSET, assetId.value().toString(), assetProfile.id(), NOW));
        repository.saveBinding(new CvProfileBinding(BindingScope.CATEGORY, categoryId.slug(), categoryProfile.id(), NOW));
        CvProfileResolver resolver =
                new CvProfileResolver(new CvProfileCache(repository, new CvProfileCacheSettings(Duration.ofMinutes(5))));

        EffectiveProfile resolved = resolver.resolve(assetId, categoryId, groupId, PipelineConfig.defaults());

        assertEquals(ProfileSource.ASSET, resolved.source());
        assertEquals(peoplePolicy.model(), resolved.config().model(), "the intent-seeded knob survives the fold");
        assertEquals(Set.of("person"), resolved.config().labelFilter(), "the intent-seeded knob survives the fold");
        assertEquals(0.55, resolved.config().confidenceThreshold(),
                "the operator's own explicit knob survives the fold, unreplaced by category's or PEOPLE's own value");
        assertEquals(12, resolved.config().inferenceFps(), "the operator's own explicit knob survives the fold");
    }

    /**
     * Wave W7.1 (docs/plans/active/CV-ORCHESTRATION-PLAN.md &sect;4.7, decision E22): the fold across
     * tiers is now genuinely per-knob, not wholesale-replace. This test replaces the pre-W7 {@code
     * assetBindingReplacesEveryKnobWholesaleRatherThanOnlyTheOnesThatDifferFromCategory}, which pinned
     * the opposite (now-false) contract -- an organization profile that sets only {@code
     * inferenceFps} and an asset profile that sets only {@code model} must both survive the fold,
     * each correctly attributed in {@link EffectiveProfile#sources()}.
     */
    @Test
    void organizationInferenceFpsAndAssetModelBothPersistWithCorrectPerKnobSources() {
        AssetId assetId = AssetId.random();
        CategoryId categoryId = new CategoryId("quadcopter");
        GroupId groupId = GroupId.random();

        CvProfile orgProfile = new CvProfile(CvProfileId.random(), "org-fps-only", "", false, groupId, null, null,
                12, null, null, null, null, null, null, NOW, NOW);
        CvProfile assetProfile = new CvProfile(CvProfileId.random(), "asset-model-only", "", false, groupId,
                new ModelRef("orion12l.pt", "latest"), null, null, null, null, null, null, null, null, NOW, NOW);

        InMemoryCvProfileRepositoryPort repository = new InMemoryCvProfileRepositoryPort();
        repository.save(orgProfile);
        repository.save(assetProfile);
        repository.saveBinding(new CvProfileBinding(BindingScope.ORGANIZATION, groupId.value().toString(), orgProfile.id(), NOW));
        repository.saveBinding(new CvProfileBinding(BindingScope.ASSET, assetId.value().toString(), assetProfile.id(), NOW));
        CvProfileResolver resolver =
                new CvProfileResolver(new CvProfileCache(repository, new CvProfileCacheSettings(Duration.ofMinutes(5))));
        PipelineConfig platformDefault = PipelineConfig.defaults();

        EffectiveProfile resolved = resolver.resolve(assetId, categoryId, groupId, platformDefault);

        assertEquals(ProfileSource.ASSET, resolved.source(), "asset is still the most specific bound tier");
        assertEquals(assetProfile.id(), resolved.profileId());
        assertEquals(12, resolved.config().inferenceFps(), "organization's own knob survives past the asset tier");
        assertEquals(new ModelRef("orion12l.pt", "latest"), resolved.config().model(), "asset's own knob wins");
        assertEquals(ProfileSource.ORGANIZATION, resolved.sources().inferenceFps());
        assertEquals(ProfileSource.ASSET, resolved.sources().model());
        assertEquals(platformDefault.confidenceThreshold(), resolved.config().confidenceThreshold(),
                "a knob neither tier set still falls all the way through to the platform default");
        assertEquals(ProfileSource.PLATFORM, resolved.sources().confidenceThreshold());
        assertNull(resolved.intent(), "neither tier set an intent");
    }

    /**
     * Wave W7.1: a tier's own {@code intent}, left to seed the four knobs {@link
     * IntentPolicyResolver} actually resolves, is attributed to {@link ProfileSource#INTENT} in
     * {@link EffectiveProfile#sources()} and survives past a more specific tier that only sets an
     * unrelated knob -- while {@link EffectiveProfile#intent()} still names the organization tier,
     * since the asset tier never set an intent of its own.
     */
    @Test
    void organizationIntentSeedsFourKnobsAndAssetExplicitConfidenceThresholdWinsOverThem() {
        AssetId assetId = AssetId.random();
        CategoryId categoryId = new CategoryId("quadcopter");
        GroupId groupId = GroupId.random();

        CvProfile orgProfile = new CvProfile(CvProfileId.random(), "org-intent-people", "", false, groupId, null,
                null, null, null, null, null, null, null, Intent.PEOPLE, NOW, NOW);
        CvProfile assetProfile = new CvProfile(CvProfileId.random(), "asset-confidence-only", "", false, groupId,
                null, 0.77, null, null, null, null, null, null, null, NOW, NOW);

        InMemoryCvProfileRepositoryPort repository = new InMemoryCvProfileRepositoryPort();
        repository.save(orgProfile);
        repository.save(assetProfile);
        repository.saveBinding(new CvProfileBinding(BindingScope.ORGANIZATION, groupId.value().toString(), orgProfile.id(), NOW));
        repository.saveBinding(new CvProfileBinding(BindingScope.ASSET, assetId.value().toString(), assetProfile.id(), NOW));
        CvProfileResolver resolver =
                new CvProfileResolver(new CvProfileCache(repository, new CvProfileCacheSettings(Duration.ofMinutes(5))));
        IntentPolicy peoplePolicy = IntentPolicyResolver.resolve(Intent.PEOPLE, List.of());

        EffectiveProfile resolved = resolver.resolve(assetId, categoryId, groupId, PipelineConfig.defaults());

        assertEquals(peoplePolicy.model(), resolved.config().model(), "intent-seeded, no tier set model explicitly");
        assertEquals(peoplePolicy.classSet(), resolved.config().labelFilter());
        assertEquals(peoplePolicy.rateCeiling(), resolved.config().inferenceFps());
        assertEquals(0.77, resolved.config().confidenceThreshold(), "asset's own explicit knob outranks the intent seed");
        assertEquals(ProfileSource.INTENT, resolved.sources().model());
        assertEquals(ProfileSource.INTENT, resolved.sources().labelFilter());
        assertEquals(ProfileSource.INTENT, resolved.sources().inferenceFps());
        assertEquals(ProfileSource.ASSET, resolved.sources().confidenceThreshold());
        assertEquals(Intent.PEOPLE, resolved.intent(), "the organization tier is the most specific one that set an intent");
    }

    /**
     * Design point 1/3 (docs/plans/active/CV-ORCHESTRATION-PLAN.md &sect;4.7): every one of the four
     * built-in profiles (storage/persistence's {@code V29__cv_profiles.sql}) fully specifies every
     * knob, so folding one through the resolver must still be byte-identical to calling its own
     * {@link CvProfile#foldOnto(PipelineConfig)} directly -- the per-knob rewrite must not change
     * behavior at all for a profile that never leaves anything to inherit.
     */
    @Test
    void fourBuiltInShapedProfilesFoldThroughTheResolverByteIdenticalToFoldOntoDirectly() {
        GroupId groupId = GroupId.random();
        PipelineConfig platformDefault = PipelineConfig.defaults();
        List<CvProfile> builtIns = List.of(
                fullySpecified("people-vehicles", new ModelRef("yolo26n.pt", "latest"), 0.40, 10, List.of(), true),
                fullySpecified("wide-search", new ModelRef("yoloe-26s-seg-pf.pt", "latest"), 0.30, 4, List.of(), true),
                fullySpecified("military-vehicles", new ModelRef("orion12l.pt", "latest"), 0.45, 5, List.of(), true),
                fullySpecified("video-only", new ModelRef("yolo26n.pt", "latest"), 0.40, 10, List.of(), false));

        for (CvProfile builtIn : builtIns) {
            AssetId assetId = AssetId.random();
            CvProfileResolver resolver = resolverWith(builtIn,
                    new CvProfileBinding(BindingScope.ASSET, assetId.value().toString(), builtIn.id(), NOW));

            EffectiveProfile resolved =
                    resolver.resolve(assetId, new CategoryId("quadcopter"), groupId, platformDefault);

            assertEquals(builtIn.foldOnto(platformDefault), resolved.config(),
                    builtIn.name() + " must fold byte-identical through the resolver as it does directly");
        }
    }

    /** A profile with every one of the eight knobs set, mirroring a built-in row's own shape. */
    private static CvProfile fullySpecified(String name, ModelRef model, double confidenceThreshold,
                                             int inferenceFps, List<String> labelFilter, boolean detectionEnabled) {
        return new CvProfile(CvProfileId.random(), name, "", true, null, model, confidenceThreshold, inferenceFps,
                labelFilter, List.of(), detectionEnabled,
                new TrackingKnobPatch(TrackingMode.ASSOCIATE, "", 0, 2000, 15), EventRuleConfig.defaults(), null,
                NOW, NOW);
    }
}

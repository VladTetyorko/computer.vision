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
                10, List.of(), List.of(), true, TrackingConfig.off(), EventRuleConfig.defaults(), NOW, NOW);
    }

    /** Like {@link #profile(String, GroupId, String)}, but with a caller-chosen {@code
     *  confidenceThreshold}/{@code inferenceFps}/{@code labelFilter} -- lets a test build a profile
     *  whose fields came from a mix of sources (e.g. an intent-seeded model/labelFilter alongside an
     *  operator's own explicit confidence/fps) without pretending {@link CvProfile} itself has any
     *  memory of that mix (it doesn't -- every field is just a value once persisted). */
    private static CvProfile profile(String name, GroupId groupId, ModelRef model, double confidenceThreshold,
                                      int inferenceFps, List<String> labelFilter) {
        return new CvProfile(CvProfileId.random(), name, "", false, groupId, model, confidenceThreshold,
                inferenceFps, labelFilter, List.of(), true, TrackingConfig.off(), EventRuleConfig.defaults(), NOW,
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
     * Patch-over-seed, tier 2: {@link CvProfileResolver}'s fold is wholesale-replace, never a
     * per-knob merge across tiers -- this is the resolver's own documented contract (its class
     * javadoc: "every CvProfile fully specifies every field... the more specific tier's complete
     * config simply supersedes the previous tier's"), pinned here so a future change that tried to
     * make the fold a genuine per-field merge across *tiers* (as opposed to within one profile's own
     * creation, which is where the real per-knob patching in this codebase lives -- see the test
     * above and CvProfileRequestTest) would fail loudly instead of silently changing behaviour.
     */
    @Test
    void assetBindingReplacesEveryKnobWholesaleRatherThanOnlyTheOnesThatDifferFromCategory() {
        AssetId assetId = AssetId.random();
        CategoryId categoryId = new CategoryId("quadcopter");
        GroupId groupId = GroupId.random();

        CvProfile categoryProfile =
                profile("people-vehicles", groupId, new ModelRef("yolo26n.pt", "latest"), 0.40, 10, List.of("person"));
        // Differs from categoryProfile in exactly one field (confidenceThreshold) -- if the fold ever
        // became a per-knob merge, model/inferenceFps/labelFilter below would leak in from
        // categoryProfile instead of asset's own (identical, in this case) values.
        CvProfile assetProfile =
                profile("mast-cams", groupId, new ModelRef("yolo26n.pt", "latest"), 0.90, 10, List.of("person"));

        InMemoryCvProfileRepositoryPort repository = new InMemoryCvProfileRepositoryPort();
        repository.save(assetProfile);
        repository.save(categoryProfile);
        repository.saveBinding(new CvProfileBinding(BindingScope.ASSET, assetId.value().toString(), assetProfile.id(), NOW));
        repository.saveBinding(new CvProfileBinding(BindingScope.CATEGORY, categoryId.slug(), categoryProfile.id(), NOW));
        CvProfileResolver resolver =
                new CvProfileResolver(new CvProfileCache(repository, new CvProfileCacheSettings(Duration.ofMinutes(5))));

        EffectiveProfile resolved = resolver.resolve(assetId, categoryId, groupId, PipelineConfig.defaults());

        assertEquals(assetProfile.id(), resolved.profileId());
        assertEquals(0.90, resolved.config().confidenceThreshold(), "asset's own differing knob wins");
        assertEquals(assetProfile.model(), resolved.config().model(), "asset's own matching knob is still asset's, not category's, value");
        assertEquals(assetProfile.inferenceFps(), resolved.config().inferenceFps());
        assertEquals(Set.copyOf(assetProfile.labelFilter()), resolved.config().labelFilter());
    }
}

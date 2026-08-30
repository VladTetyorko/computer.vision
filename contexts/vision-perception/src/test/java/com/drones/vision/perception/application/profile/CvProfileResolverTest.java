package com.drones.vision.perception.application.profile;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.perception.domain.model.BindingScope;
import com.drones.vision.perception.domain.model.CvProfile;
import com.drones.vision.perception.domain.model.CvProfileBinding;
import com.drones.vision.perception.domain.model.CvProfileId;
import com.drones.vision.perception.domain.model.EventRuleConfig;
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
    void maxInFlightInferencesAlwaysComesFromThePlatformDefaultEvenWhenAProfileMatches() {
        AssetId assetId = AssetId.random();
        GroupId groupId = GroupId.random();
        CvProfile matched = profile("mast-cams", groupId, "yolo26n.pt");
        CvProfileResolver resolver = resolverWith(matched,
                new CvProfileBinding(BindingScope.ASSET, assetId.value().toString(), matched.id(), NOW));
        PipelineConfig platformDefault = new PipelineConfig(new ModelRef("other", "v1"), 0.9, 30, 17, Set.of(),
                EventRuleConfig.defaults(), false, TrackingConfig.off(), Set.of());

        EffectiveProfile resolved = resolver.resolve(assetId, new CategoryId("fixed-camera"), groupId, platformDefault);

        assertEquals(17, resolved.config().maxInFlightInferences(), "host capacity is never a profile concern");
        assertEquals(matched.model(), resolved.config().model(), "every other field comes from the matched profile");
    }
}

package com.drones.vision.perception.application.profile;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.LifecycleState;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.perception.domain.model.BindingScope;
import com.drones.vision.perception.domain.model.CvProfile;
import com.drones.vision.perception.domain.model.CvProfileBinding;
import com.drones.vision.perception.domain.model.CvProfileId;
import com.drones.vision.perception.domain.model.EventRuleConfig;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.perception.domain.model.TrackingConfig;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.application.asset.AssetDetails;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.asset.AssetStatus;
import com.drones.vision.warehouse.application.asset.AssetSummary;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.Identity;
import com.drones.vision.warehouse.domain.model.InventoryState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultCvProfileService}: scope gate, built-in/still-bound refusals, scopeId validation,
 * and the {@code effective}/{@code coverage} read paths.
 */
class DefaultCvProfileServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private InMemoryCvProfileRepositoryPort repository;
    private CvProfileCache cache;
    private AssetService assetService;
    private AuditTrailPort auditTrail;
    private DefaultCvProfileService service;
    private UserId actor;

    @BeforeEach
    void setUp() {
        repository = new InMemoryCvProfileRepositoryPort();
        cache = new CvProfileCache(repository, new CvProfileCacheSettings(Duration.ofMinutes(5)));
        assetService = mock(AssetService.class);
        auditTrail = mock(AuditTrailPort.class);
        actor = UserId.random();
        service = new DefaultCvProfileService(cache, new CvProfileResolver(cache), assetService, auditTrail, () -> NOW);
    }

    private static CvProfile builtIn(String name) {
        return new CvProfile(CvProfileId.random(), name, "", true, null, new ModelRef("yolo26n.pt", "latest"), 0.4,
                10, List.of(), List.of(), true, TrackingConfig.off(), EventRuleConfig.defaults(), NOW, NOW);
    }

    private static CvProfile owned(String name, GroupId groupId) {
        return new CvProfile(CvProfileId.random(), name, "", false, groupId, new ModelRef("yolo26n.pt", "latest"),
                0.4, 10, List.of(), List.of(), true, TrackingConfig.off(), EventRuleConfig.defaults(), NOW, NOW);
    }

    private static CvProfileSpec spec(String name) {
        return new CvProfileSpec(name, "", new ModelRef("yolo26n.pt", "latest"), 0.4, 10, List.of(), List.of(), true,
                TrackingConfig.off(), EventRuleConfig.defaults());
    }

    private static Asset asset(AssetId id, CategoryId category, GroupId groupId) {
        return new Asset(id, "Mast North", category, new Ownership(UserId.random(), groupId), Set.of(),
                java.util.Map.of(), LifecycleState.ACTIVE, Identity.NONE, Custody.NONE, InventoryState.IN_STOCK, NOW,
                NOW);
    }

    private static AssetDetails detailsOf(Asset asset) {
        AssetSummary summary = new AssetSummary(asset, "Fixed camera", AssetStatus.OFFLINE, null, null,
                asset.inventoryState(), asset.identity(), asset.custody());
        return new AssetDetails(summary, List.of(), List.of());
    }

    // --- list / get -------------------------------------------------------------------------

    @Test
    void listIncludesEveryBuiltInAndOnlyOwnGroupProfilesOtherwise() {
        GroupId ownGroup = GroupId.random();
        GroupId otherGroup = GroupId.random();
        CvProfile builtIn = cache.save(builtIn("people-vehicles"));
        CvProfile own = cache.save(owned("mast-cams", ownGroup));
        cache.save(owned("someone-elses", otherGroup));
        VisibilityScope scope = VisibilityScope.groups(Set.of(ownGroup));

        List<CvProfile> visible = service.list(actor, scope);

        assertEquals(Set.of(builtIn, own), Set.copyOf(visible));
    }

    @Test
    void getThrowsNoSuchElementForANonBuiltInProfileOutsideScope() {
        CvProfile foreign = cache.save(owned("someone-elses", GroupId.random()));
        VisibilityScope scope = VisibilityScope.groups(Set.of(GroupId.random()));

        assertThrows(NoSuchElementException.class, () -> service.get(foreign.id(), actor, scope));
    }

    @Test
    void getReturnsABuiltInProfileRegardlessOfScope() {
        CvProfile builtIn = cache.save(builtIn("wide-search"));
        VisibilityScope scope = VisibilityScope.groups(Set.of(GroupId.random()));

        assertEquals(builtIn, service.get(builtIn.id(), actor, scope));
    }

    // --- create -------------------------------------------------------------------------------

    @Test
    void createDeniedWhenScopeCannotManageOrgAndAuditsTheDenial() {
        VisibilityScope scope = VisibilityScope.assignedAssets(Set.of());

        assertThrows(AccessDeniedException.class,
                () -> service.create(spec("mast-cams"), GroupId.random(), actor, scope));

        verify(auditTrail).record(any(AuditEntry.class));
        assertTrue(cache.snapshot().profiles().isEmpty(), "a denied create must not persist anything");
    }

    @Test
    void createSucceedsForAManagerAndAudits() {
        GroupId groupId = GroupId.random();
        VisibilityScope scope = VisibilityScope.groups(Set.of(groupId));

        CvProfile created = service.create(spec("mast-cams"), groupId, actor, scope);

        assertEquals("mast-cams", created.name());
        assertFalse(created.builtIn());
        assertEquals(groupId, created.groupId());
        assertEquals(1, cache.snapshot().profiles().size());
        verify(auditTrail).record(any(AuditEntry.class));
    }

    // --- update -------------------------------------------------------------------------------

    @Test
    void updateRefusesABuiltInProfile() {
        CvProfile builtIn = cache.save(builtIn("people-vehicles"));
        VisibilityScope scope = VisibilityScope.unbounded();

        assertThrows(IllegalStateException.class, () -> service.update(builtIn.id(), spec("renamed"), actor, scope));
        assertEquals("people-vehicles", cache.snapshot().findById(builtIn.id()).orElseThrow().name(),
                "a refused edit must not change the built-in");
    }

    @Test
    void updateDeniedWhenScopeCannotManageOrg() {
        GroupId groupId = GroupId.random();
        CvProfile existing = cache.save(owned("mast-cams", groupId));
        VisibilityScope scope = VisibilityScope.assignedAssets(Set.of());

        assertThrows(AccessDeniedException.class, () -> service.update(existing.id(), spec("renamed"), actor, scope));
    }

    @Test
    void updateAppliesTheNewFieldsAndKeepsCreatedAt() {
        GroupId groupId = GroupId.random();
        CvProfile existing = cache.save(owned("mast-cams", groupId));
        VisibilityScope scope = VisibilityScope.unbounded();

        CvProfile updated = service.update(existing.id(), spec("mast-cams-v2"), actor, scope);

        assertEquals("mast-cams-v2", updated.name());
        assertEquals(existing.createdAt(), updated.createdAt());
    }

    // --- delete -------------------------------------------------------------------------------

    @Test
    void deleteRefusesABuiltInProfile() {
        CvProfile builtIn = cache.save(builtIn("wide-search"));
        VisibilityScope scope = VisibilityScope.unbounded();

        assertThrows(IllegalStateException.class, () -> service.delete(builtIn.id(), actor, scope));
        assertTrue(cache.snapshot().findById(builtIn.id()).isPresent());
    }

    @Test
    void deleteRefusesAStillBoundProfile() {
        GroupId groupId = GroupId.random();
        CvProfile profile = cache.save(owned("mast-cams", groupId));
        cache.saveBinding(new CvProfileBinding(BindingScope.ORGANIZATION, groupId.value().toString(), profile.id(), NOW));
        VisibilityScope scope = VisibilityScope.unbounded();

        assertThrows(IllegalStateException.class, () -> service.delete(profile.id(), actor, scope));
        assertTrue(cache.snapshot().findById(profile.id()).isPresent());
    }

    @Test
    void deleteSucceedsForAnUnboundNonBuiltInProfile() {
        GroupId groupId = GroupId.random();
        CvProfile profile = cache.save(owned("mast-cams", groupId));
        VisibilityScope scope = VisibilityScope.unbounded();

        service.delete(profile.id(), actor, scope);

        assertTrue(cache.snapshot().findById(profile.id()).isEmpty());
    }

    // --- fork ---------------------------------------------------------------------------------

    @Test
    void forkCopiesABuiltInIntoANewGroupOwnedProfileLeavingTheSourceUntouched() {
        CvProfile source = cache.save(builtIn("people-vehicles"));
        GroupId groupId = GroupId.random();
        VisibilityScope scope = VisibilityScope.groups(Set.of(groupId));

        CvProfile forked = service.fork(source.id(), "my-people-vehicles", groupId, actor, scope);

        assertFalse(forked.builtIn());
        assertEquals(groupId, forked.groupId());
        assertEquals("my-people-vehicles", forked.name());
        assertEquals(source.model(), forked.model());
        assertTrue(cache.snapshot().findById(source.id()).orElseThrow().builtIn(), "the source stays built-in");
    }

    @Test
    void forkRefusesANonBuiltInSource() {
        GroupId groupId = GroupId.random();
        CvProfile own = cache.save(owned("mast-cams", groupId));
        VisibilityScope scope = VisibilityScope.unbounded();

        assertThrows(IllegalArgumentException.class, () -> service.fork(own.id(), "copy", groupId, actor, scope));
    }

    // --- bind / unbind --------------------------------------------------------------------------

    @Test
    void bindRejectsAMalformedScopeIdForEachKind() {
        CvProfile profile = cache.save(builtIn("people-vehicles"));
        VisibilityScope scope = VisibilityScope.unbounded();

        assertThrows(IllegalArgumentException.class,
                () -> service.bind(BindingScope.ASSET, "not-a-uuid", profile.id(), actor, scope));
        assertThrows(IllegalArgumentException.class,
                () -> service.bind(BindingScope.ORGANIZATION, "not-a-uuid", profile.id(), actor, scope));
        assertThrows(IllegalArgumentException.class,
                () -> service.bind(BindingScope.CATEGORY, "Not_Kebab", profile.id(), actor, scope));
    }

    @Test
    void bindAcceptsAWellFormedScopeIdForEachKind() {
        CvProfile profile = cache.save(builtIn("people-vehicles"));
        VisibilityScope scope = VisibilityScope.unbounded();

        CvProfileBinding assetBinding =
                service.bind(BindingScope.ASSET, AssetId.random().value().toString(), profile.id(), actor, scope);
        CvProfileBinding orgBinding =
                service.bind(BindingScope.ORGANIZATION, GroupId.random().value().toString(), profile.id(), actor, scope);
        CvProfileBinding categoryBinding = service.bind(BindingScope.CATEGORY, "quadcopter", profile.id(), actor, scope);

        assertEquals(profile.id(), assetBinding.profileId());
        assertEquals(profile.id(), orgBinding.profileId());
        assertEquals(profile.id(), categoryBinding.profileId());
        assertEquals(3, cache.snapshot().bindings().size());
    }

    @Test
    void bindDeniedWhenScopeCannotManageOrg() {
        CvProfile profile = cache.save(builtIn("people-vehicles"));
        VisibilityScope scope = VisibilityScope.assignedAssets(Set.of());

        assertThrows(AccessDeniedException.class,
                () -> service.bind(BindingScope.CATEGORY, "quadcopter", profile.id(), actor, scope));
    }

    @Test
    void bindThrowsNoSuchElementForAnUnknownProfile() {
        VisibilityScope scope = VisibilityScope.unbounded();

        assertThrows(NoSuchElementException.class,
                () -> service.bind(BindingScope.CATEGORY, "quadcopter", CvProfileId.random(), actor, scope));
    }

    @Test
    void unbindIsIdempotentForAnAlreadyUnboundScope() {
        VisibilityScope scope = VisibilityScope.unbounded();

        service.unbind(BindingScope.CATEGORY, "quadcopter", actor, scope);
        service.unbind(BindingScope.CATEGORY, "quadcopter", actor, scope);

        assertTrue(cache.snapshot().bindings().isEmpty());
    }

    @Test
    void unbindRemovesAnExistingBinding() {
        CvProfile profile = cache.save(builtIn("people-vehicles"));
        VisibilityScope scope = VisibilityScope.unbounded();
        service.bind(BindingScope.CATEGORY, "quadcopter", profile.id(), actor, scope);

        service.unbind(BindingScope.CATEGORY, "quadcopter", actor, scope);

        assertTrue(cache.snapshot().bindings().isEmpty());
    }

    // --- effective / coverage ------------------------------------------------------------------

    @Test
    void effectiveResolvesThroughTheSameAssetCategoryOrganizationFold() {
        GroupId groupId = GroupId.random();
        AssetId assetId = AssetId.random();
        CategoryId categoryId = new CategoryId("fixed-camera");
        Asset asset = asset(assetId, categoryId, groupId);
        VisibilityScope scope = VisibilityScope.unbounded();
        when(assetService.details(eq(scope), eq(assetId))).thenReturn(detailsOf(asset));
        CvProfile bound = cache.save(owned("mast-cams", groupId));
        service.bind(BindingScope.ASSET, assetId.value().toString(), bound.id(), actor, scope);

        EffectiveProfile effective = service.effective(assetId, PipelineConfig.defaults(), actor, scope);

        assertEquals(ProfileSource.ASSET, effective.source());
        assertEquals(bound.id(), effective.profileId());
    }

    @Test
    void effectiveThrowsNoSuchElementWhenAssetServiceHidesTheAsset() {
        AssetId assetId = AssetId.random();
        VisibilityScope scope = VisibilityScope.groups(Set.of(GroupId.random()));
        when(assetService.details(eq(scope), eq(assetId))).thenThrow(new NoSuchElementException("hidden"));

        assertThrows(NoSuchElementException.class,
                () -> service.effective(assetId, PipelineConfig.defaults(), actor, scope));
    }

    @Test
    void coverageReturnsOneRowPerAssetTheScopeMaySee() {
        GroupId groupId = GroupId.random();
        VisibilityScope scope = VisibilityScope.groups(Set.of(groupId));
        Asset first = asset(AssetId.random(), new CategoryId("quadcopter"), groupId);
        Asset second = asset(AssetId.random(), new CategoryId("fixed-camera"), groupId);
        when(assetService.assets(scope, false)).thenReturn(List.of(detailsOf(first).summary(), detailsOf(second).summary()));

        List<CoverageRow> rows = service.coverage(PipelineConfig.defaults(), actor, scope);

        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(row -> row.source() == ProfileSource.PLATFORM));
    }
}

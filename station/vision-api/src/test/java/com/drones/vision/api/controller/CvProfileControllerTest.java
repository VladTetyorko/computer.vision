package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.perception.application.profile.CoverageRow;
import com.drones.vision.perception.application.profile.CvProfileService;
import com.drones.vision.perception.application.profile.EffectiveProfile;
import com.drones.vision.perception.application.profile.ProfileSource;
import com.drones.vision.perception.domain.model.BindingScope;
import com.drones.vision.perception.domain.model.CvProfile;
import com.drones.vision.perception.domain.model.CvProfileBinding;
import com.drones.vision.perception.domain.model.CvProfileId;
import com.drones.vision.perception.domain.model.EventRuleConfig;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.perception.domain.model.TrackingConfig;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.Authority;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.application.category.CategoryService;
import com.drones.vision.warehouse.domain.model.DeviceCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for {@link CvProfileController} (docs/plans/active/CV-SETTINGS-PLAN.md &sect;5.2's
 * frozen wire contract), mirroring {@link ModelRegistryControllerTest}'s style: a standalone {@code
 * MockMvc} over mocked {@link CvProfileService}/{@link CategoryService} collaborators plus a real
 * {@link PipelineConfig#defaults()} platform default, with {@link ApiExceptionHandler} attached so
 * error mapping is exercised exactly as it runs in production.
 */
class CvProfileControllerTest {

    private static final GroupId GROUP_ID = GroupId.random();
    private static final ModelRef MODEL = new ModelRef("yolo26n.pt", "latest");

    private CvProfileService cvProfileService;
    private CategoryService categoryService;
    private final PipelineConfig platformDefault = PipelineConfig.defaults();
    private MockMvc mockMvc;

    private final UserId ownerId = UserId.random();
    private final Ownership ownership = new Ownership(ownerId, GROUP_ID);
    private final CurrentUser currentUser = new CurrentUser(ownership);

    @BeforeEach
    void setUp() {
        cvProfileService = mock(CvProfileService.class);
        categoryService = mock(CategoryService.class);
        when(categoryService.categories()).thenReturn(List.of());
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CvProfileController(cvProfileService, categoryService, platformDefault,
                        currentUser))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static CvProfile profile(CvProfileId id, boolean builtIn, GroupId groupId) {
        return new CvProfile(id, "people-vehicles", "Built-in template", builtIn, groupId, MODEL, 0.5, 10,
                List.of("person", "car"), List.of(), true, TrackingConfig.defaults(), EventRuleConfig.defaults(),
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-01T00:00:00Z"));
    }

    private static final String PROFILE_REQUEST_BODY = "{"
            + "\"name\":\"people-vehicles\",\"description\":\"desc\",\"model\":\"yolo26n.pt\","
            + "\"confidenceThreshold\":0.5,\"inferenceFps\":10,\"labelFilter\":[\"person\"],"
            + "\"labelDenyFilter\":[],\"detectionEnabled\":true,"
            + "\"tracking\":{\"mode\":\"ASSOCIATE\",\"engineId\":\"\",\"capabilityLevel\":0,"
            + "\"verifyEveryMillis\":2000,\"followFps\":15}}";

    // ---- GET /api/cv/profiles ----

    @Test
    void listReturns200WrappedUnderProfilesAndThreadsActorAndScope() throws Exception {
        CvProfileId id = CvProfileId.random();
        when(cvProfileService.list(ownerId, currentUser.scope())).thenReturn(List.of(profile(id, true, null)));

        mockMvc.perform(get("/api/cv/profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profiles[0].id").value(id.value().toString()))
                .andExpect(jsonPath("$.profiles[0].builtIn").value(true))
                .andExpect(jsonPath("$.profiles[0].groupId").doesNotExist())
                .andExpect(jsonPath("$.profiles[0].model").value("yolo26n.pt"))
                .andExpect(jsonPath("$.profiles[0].tracking.mode").value("ASSOCIATE"))
                .andExpect(jsonPath("$.profiles[0].eventRule.absenceToCloseSeconds").value(5));

        verify(cvProfileService).list(ownerId, currentUser.scope());
    }

    // ---- GET /api/cv/profiles/{id} ----

    @Test
    void getReturns200WithTheProfile() throws Exception {
        CvProfileId id = CvProfileId.random();
        when(cvProfileService.get(id, ownerId, currentUser.scope())).thenReturn(profile(id, false, GROUP_ID));

        mockMvc.perform(get("/api/cv/profiles/{id}", id.value().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.value().toString()))
                .andExpect(jsonPath("$.groupId").value(GROUP_ID.value().toString()));
    }

    @Test
    void getReturns404ForAnUnknownProfile() throws Exception {
        CvProfileId id = CvProfileId.random();
        when(cvProfileService.get(eq(id), eq(ownerId), any())).thenThrow(new java.util.NoSuchElementException(
                "Unknown CV profile: " + id.value()));

        mockMvc.perform(get("/api/cv/profiles/{id}", id.value().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void getReturns400ForAMalformedId() throws Exception {
        mockMvc.perform(get("/api/cv/profiles/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    // ---- POST /api/cv/profiles ----

    @Test
    void createReturns201AndThreadsTheCallersOwnGroup() throws Exception {
        CvProfileId id = CvProfileId.random();
        when(cvProfileService.create(any(), eq(GROUP_ID), eq(ownerId), eq(currentUser.authority())))
                .thenReturn(profile(id, false, GROUP_ID));

        mockMvc.perform(post("/api/cv/profiles").contentType(MediaType.APPLICATION_JSON)
                        .content(PROFILE_REQUEST_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.value().toString()));

        verify(cvProfileService).create(any(), eq(GROUP_ID), eq(ownerId), eq(currentUser.authority()));
    }

    @Test
    void createReturns403WhenCallerMayNotManageTheOrganization() throws Exception {
        when(cvProfileService.create(any(), any(), eq(ownerId), any()))
                .thenThrow(new AccessDeniedException("Not permitted to create CV profiles"));

        mockMvc.perform(post("/api/cv/profiles").contentType(MediaType.APPLICATION_JSON)
                        .content(PROFILE_REQUEST_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void createReturns400ForAnUnknownTrackingMode() throws Exception {
        String body = PROFILE_REQUEST_BODY.replace("\"mode\":\"ASSOCIATE\"", "\"mode\":\"BOGUS\"");

        mockMvc.perform(post("/api/cv/profiles").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    // ---- PUT /api/cv/profiles/{id} ----

    @Test
    void updateReturns200WithTheUpdatedProfile() throws Exception {
        CvProfileId id = CvProfileId.random();
        when(cvProfileService.update(eq(id), any(), eq(ownerId), eq(currentUser.authority())))
                .thenReturn(profile(id, false, GROUP_ID));

        mockMvc.perform(put("/api/cv/profiles/{id}", id.value().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(PROFILE_REQUEST_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.value().toString()));
    }

    @Test
    void updateReturns409ForABuiltInProfile() throws Exception {
        CvProfileId id = CvProfileId.random();
        when(cvProfileService.update(eq(id), any(), eq(ownerId), any()))
                .thenThrow(new IllegalStateException("Built-in CV profile cannot be edited: " + id.value()));

        mockMvc.perform(put("/api/cv/profiles/{id}", id.value().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(PROFILE_REQUEST_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    // ---- DELETE /api/cv/profiles/{id} ----

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/api/cv/profiles/{id}", CvProfileId.random().value().toString()))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteReturns409WhenStillBound() throws Exception {
        CvProfileId id = CvProfileId.random();
        doThrow(new IllegalStateException("CV profile is still bound to 1 scope(s): " + id.value()))
                .when(cvProfileService).delete(eq(id), eq(ownerId), any());

        mockMvc.perform(delete("/api/cv/profiles/{id}", id.value().toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    // ---- PUT /api/cv/bindings ----

    @Test
    void bindReturns200WithThePersistedBinding() throws Exception {
        CvProfileId profileId = CvProfileId.random();
        Instant createdAt = Instant.parse("2026-08-01T00:00:00Z");
        when(cvProfileService.bind(eq(BindingScope.ASSET), eq("11111111-1111-1111-1111-111111111111"),
                eq(profileId), eq(ownerId), eq(currentUser.authority())))
                .thenReturn(new CvProfileBinding(BindingScope.ASSET, "11111111-1111-1111-1111-111111111111",
                        profileId, createdAt));

        mockMvc.perform(put("/api/cv/bindings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopeKind\":\"ASSET\",\"scopeId\":\"11111111-1111-1111-1111-111111111111\","
                                + "\"profileId\":\"" + profileId.value() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeKind").value("ASSET"))
                .andExpect(jsonPath("$.profileId").value(profileId.value().toString()));
    }

    @Test
    void bindReturns400WhenProfileIdIsMissing() throws Exception {
        mockMvc.perform(put("/api/cv/bindings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopeKind\":\"ASSET\",\"scopeId\":\"11111111-1111-1111-1111-111111111111\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void bindReturns400ForAnUnknownScopeKind() throws Exception {
        mockMvc.perform(put("/api/cv/bindings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopeKind\":\"BOGUS\",\"scopeId\":\"x\",\"profileId\":\""
                                + CvProfileId.random().value() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    // ---- DELETE /api/cv/bindings ----

    @Test
    void unbindReturns204() throws Exception {
        mockMvc.perform(delete("/api/cv/bindings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopeKind\":\"CATEGORY\",\"scopeId\":\"fpv-drone\"}"))
                .andExpect(status().isNoContent());

        verify(cvProfileService).unbind(BindingScope.CATEGORY, "fpv-drone", ownerId, currentUser.authority());
    }

    // ---- GET /api/cv/profiles/effective ----

    @Test
    void effectiveReturnsTheRealFetchedProfileWhenABindingMatched() throws Exception {
        AssetId assetId = AssetId.random();
        CvProfileId profileId = CvProfileId.random();
        when(cvProfileService.effective(eq(assetId), eq(platformDefault), eq(ownerId), eq(currentUser.scope())))
                .thenReturn(new EffectiveProfile(assetId, profileId, "people-vehicles", ProfileSource.ASSET,
                        platformDefault));
        when(cvProfileService.get(profileId, ownerId, currentUser.scope()))
                .thenReturn(profile(profileId, false, GROUP_ID));

        mockMvc.perform(get("/api/cv/profiles/effective").param("assetId", assetId.value().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(assetId.value().toString()))
                .andExpect(jsonPath("$.source").value("ASSET"))
                .andExpect(jsonPath("$.profile.id").value(profileId.value().toString()))
                .andExpect(jsonPath("$.profile.groupId").value(GROUP_ID.value().toString()));
    }

    @Test
    void effectiveSynthesizesThePlatformDefaultWhenNoBindingMatched() throws Exception {
        AssetId assetId = AssetId.random();
        when(cvProfileService.effective(eq(assetId), eq(platformDefault), eq(ownerId), eq(currentUser.scope())))
                .thenReturn(new EffectiveProfile(assetId, null, null, ProfileSource.PLATFORM, platformDefault));

        mockMvc.perform(get("/api/cv/profiles/effective").param("assetId", assetId.value().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("PLATFORM"))
                .andExpect(jsonPath("$.profile.id").value("00000000-0000-0000-0000-000000000000"))
                .andExpect(jsonPath("$.profile.name").value("Platform default"))
                .andExpect(jsonPath("$.profile.builtIn").value(true))
                .andExpect(jsonPath("$.profile.groupId").doesNotExist())
                .andExpect(jsonPath("$.profile.model").value("yolo26n.pt"));

        // No id names a profile to fetch for PLATFORM — get() must never be called.
        verify(cvProfileService, org.mockito.Mockito.never()).get(any(), any(), any());
    }

    @Test
    void effectiveReturns404WhenTheAssetIsOutOfScope() throws Exception {
        AssetId assetId = AssetId.random();
        when(cvProfileService.effective(eq(assetId), any(), eq(ownerId), any()))
                .thenThrow(new java.util.NoSuchElementException("Unknown asset: " + assetId.value()));

        mockMvc.perform(get("/api/cv/profiles/effective").param("assetId", assetId.value().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    // ---- GET /api/cv/coverage ----

    @Test
    void coverageResolvesCategoryNameAndSynthesizesThePlatformSentinelRow() throws Exception {
        AssetId boundAsset = AssetId.random();
        AssetId unboundAsset = AssetId.random();
        CategoryId categoryId = new CategoryId("fpv-drone");
        CvProfileId profileId = CvProfileId.random();
        when(categoryService.categories())
                .thenReturn(List.of(new DeviceCategory(categoryId, "FPV Drone", null, List.of(), true)));
        when(cvProfileService.coverage(eq(platformDefault), eq(ownerId), eq(currentUser.scope()))).thenReturn(List.of(
                new CoverageRow(boundAsset, "Asset One", categoryId, profileId, "people-vehicles",
                        ProfileSource.ASSET, true, MODEL, List.of("person"), List.of()),
                new CoverageRow(unboundAsset, "Asset Two", categoryId, null, null, ProfileSource.PLATFORM, false,
                        platformDefault.model(), List.of(), List.of())));

        mockMvc.perform(get("/api/cv/coverage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.rows[0].categoryName").value("FPV Drone"))
                .andExpect(jsonPath("$.rows[0].profileId").value(profileId.value().toString()))
                .andExpect(jsonPath("$.rows[1].source").value("PLATFORM"))
                .andExpect(jsonPath("$.rows[1].profileId").value("00000000-0000-0000-0000-000000000000"))
                .andExpect(jsonPath("$.rows[1].profileName").value("Platform default"));
    }

    @Test
    void coverageFallsBackToTheCategorySlugWhenTheCategoryIsUnknown() throws Exception {
        AssetId assetId = AssetId.random();
        CategoryId categoryId = new CategoryId("deleted-category");
        when(categoryService.categories()).thenReturn(List.of());
        when(cvProfileService.coverage(eq(platformDefault), eq(ownerId), eq(currentUser.scope())))
                .thenReturn(List.of(new CoverageRow(assetId, "Asset One", categoryId, null, null,
                        ProfileSource.PLATFORM, false, platformDefault.model(), List.of(), List.of())));

        mockMvc.perform(get("/api/cv/coverage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].categoryName").value("deleted-category"));
    }
}

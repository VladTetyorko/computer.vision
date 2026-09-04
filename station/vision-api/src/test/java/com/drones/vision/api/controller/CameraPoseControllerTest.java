package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.security.PrincipalResolver;
import com.drones.vision.api.support.FixedCameraGeoProperties;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.map.application.MapAccessPolicy;
import com.drones.vision.map.application.track.CameraPoseInput;
import com.drones.vision.map.application.track.CameraPoseService;
import com.drones.vision.map.domain.model.CameraPose;
import com.drones.vision.map.domain.model.CameraPoseSource;
import com.drones.vision.map.domain.model.LayerId;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.platform.Authority;
import com.drones.vision.platform.Capability;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.warehouse.application.asset.AssetDetails;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.asset.AssetStatus;
import com.drones.vision.warehouse.application.asset.AssetSummary;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.Identity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for {@link CameraPoseController} (docs/plans/done/FIXED-CAMERA-GEO-PLAN.md §5's
 * frozen wire contract, Wave G4) — flag-off {@code 409} on every method, the {@code
 * AssetController}-matching 404-vs-403 split (D10), and calibration's never-persists guarantee (D5).
 */
class CameraPoseControllerTest {

    private CameraPoseService cameraPoseService;
    private AssetService assetService;
    private MockMvc mockMvc;

    private final UserId ownerId = UserId.random();
    private final Ownership ownership = new Ownership(ownerId, GroupId.random());
    private final CurrentUser currentUser = new CurrentUser(ownership);

    @BeforeEach
    void setUp() {
        cameraPoseService = mock(CameraPoseService.class);
        assetService = mock(AssetService.class);
        mockMvc = mockMvcFor(currentUser, enabledProperties());
    }

    private static FixedCameraGeoProperties enabledProperties() {
        return new FixedCameraGeoProperties(true, 25.0);
    }

    private static FixedCameraGeoProperties disabledProperties() {
        return new FixedCameraGeoProperties(false, 25.0);
    }

    private MockMvc mockMvcFor(CurrentUser user, FixedCameraGeoProperties properties) {
        return MockMvcBuilders
                .standaloneSetup(new CameraPoseController(cameraPoseService, assetService, user, properties))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /** A {@link CurrentUser} answering with {@link #ownership}/{@link #ownerId} but a caller-supplied scope. */
    private CurrentUser currentUserWithScope(VisibilityScope scope) {
        return new CurrentUser(new PrincipalResolver() {
            @Override
            public UserId userId() {
                return ownerId;
            }

            @Override
            public Ownership ownership() {
                return ownership;
            }

            @Override
            public VisibilityScope scope() {
                return scope;
            }

            @Override
            public MapAccessPolicy.Viewer viewer() {
                throw new UnsupportedOperationException("CameraPoseController never calls viewer()");
            }

            @Override
            public Role role() {
                throw new UnsupportedOperationException("CameraPoseController never calls role()");
            }

            @Override
            public Authority authority() {
                return new Authority(scope, EnumSet.allOf(Capability.class));
            }
        });
    }

    private AssetId stubExistingAsset() {
        AssetId assetId = AssetId.random();
        Asset asset = Asset.register(assetId, "camera-1", new CategoryId("camera"), ownership,
                Set.of(DeviceId.random()), Map.of(), Identity.NONE, Custody.NONE);
        AssetSummary summary = new AssetSummary(asset, "Camera", AssetStatus.OFFLINE, null, null,
                asset.inventoryState(), asset.identity(), asset.custody());
        AssetDetails details = new AssetDetails(summary, List.of(), List.of());
        when(assetService.details(any(VisibilityScope.class), eq(assetId))).thenReturn(details);
        return assetId;
    }

    private static CameraPose pose(AssetId assetId, LayerId targetLayerId, Double rmsErrorPixels) {
        return new CameraPose(assetId, new GeoPosition(50.45, 30.52, null), 12.0, 90.0, 30.0, 60.0, targetLayerId,
                targetLayerId == null ? CameraPoseSource.MANUAL : CameraPoseSource.CALIBRATED, rmsErrorPixels,
                Instant.parse("2026-08-17T10:00:00Z"), UserId.random());
    }

    private static String putBody() {
        return """
                {"latitude":50.45,"longitude":30.52,"aglMeters":12.0,"yawDegrees":90.0,"pitchDegrees":30.0,
                "hfovDegrees":60.0,"source":"MANUAL"}
                """;
    }

    // ---- flag-off 409, every method (D8) ----

    @Test
    void getReturns409WhenDisabled() throws Exception {
        MockMvc disabledMvc = mockMvcFor(currentUser, disabledProperties());

        disabledMvc.perform(get("/api/assets/{assetId}/camera-pose", AssetId.random().value()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"))
                .andExpect(jsonPath("$.message")
                        .value("fixed-camera geolocation is disabled (vision.geo.fixed-camera.enabled)"));

        verifyNoInteractions(cameraPoseService, assetService);
    }

    @Test
    void putReturns409WhenDisabled() throws Exception {
        MockMvc disabledMvc = mockMvcFor(currentUser, disabledProperties());

        disabledMvc.perform(put("/api/assets/{assetId}/camera-pose", AssetId.random().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(putBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));

        verifyNoInteractions(cameraPoseService, assetService);
    }

    @Test
    void deleteReturns409WhenDisabled() throws Exception {
        MockMvc disabledMvc = mockMvcFor(currentUser, disabledProperties());

        disabledMvc.perform(delete("/api/assets/{assetId}/camera-pose", AssetId.random().value()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));

        verifyNoInteractions(cameraPoseService, assetService);
    }

    @Test
    void calibrateReturns409WhenDisabled() throws Exception {
        MockMvc disabledMvc = mockMvcFor(currentUser, disabledProperties());

        disabledMvc.perform(post("/api/assets/{assetId}/camera-pose/calibration", AssetId.random().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"latitude":50.45,"longitude":30.52,"aglMeters":12.0,"imageWidth":1920,
                                "imageHeight":1080,"points":[]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));

        verifyNoInteractions(cameraPoseService, assetService);
    }

    // ---- GET: visibility only (D10) ----

    @Test
    void getReturns404ForAnUnknownOrOutOfScopeAsset() throws Exception {
        AssetId assetId = AssetId.random();
        when(assetService.details(any(VisibilityScope.class), eq(assetId)))
                .thenThrow(new NoSuchElementException("Unknown asset"));

        mockMvc.perform(get("/api/assets/{assetId}/camera-pose", assetId.value()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getReturns404WhenTheAssetIsVisibleButHasNoStoredPose() throws Exception {
        AssetId assetId = stubExistingAsset();
        when(cameraPoseService.find(assetId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/assets/{assetId}/camera-pose", assetId.value()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getReturns200WithTheFullMappedPoseShape() throws Exception {
        AssetId assetId = stubExistingAsset();
        LayerId targetLayerId = LayerId.random();
        when(cameraPoseService.find(assetId)).thenReturn(Optional.of(pose(assetId, targetLayerId, 3.5)));

        mockMvc.perform(get("/api/assets/{assetId}/camera-pose", assetId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(assetId.value().toString()))
                .andExpect(jsonPath("$.latitude").value(50.45))
                .andExpect(jsonPath("$.longitude").value(30.52))
                .andExpect(jsonPath("$.aglMeters").value(12.0))
                .andExpect(jsonPath("$.yawDegrees").value(90.0))
                .andExpect(jsonPath("$.pitchDegrees").value(30.0))
                .andExpect(jsonPath("$.hfovDegrees").value(60.0))
                .andExpect(jsonPath("$.targetLayerId").value(targetLayerId.value().toString()))
                .andExpect(jsonPath("$.source").value("CALIBRATED"))
                .andExpect(jsonPath("$.rmsErrorPixels").value(3.5))
                .andExpect(jsonPath("$.updatedAt").value("2026-08-17T10:00:00Z"));
    }

    @Test
    void getOmitsTargetLayerIdAndRmsErrorPixelsForAManualPoseWithNoTargetLayer() throws Exception {
        AssetId assetId = stubExistingAsset();
        when(cameraPoseService.find(assetId)).thenReturn(Optional.of(pose(assetId, null, null)));

        mockMvc.perform(get("/api/assets/{assetId}/camera-pose", assetId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.targetLayerId").doesNotExist())
                .andExpect(jsonPath("$.rmsErrorPixels").doesNotExist());
    }

    @Test
    void getSucceedsForAVisibleButUnmanageableAsset() throws Exception {
        // D10: a caller who may see the asset but not manage it can still read its pose.
        AssetId assetId = stubExistingAsset();
        when(cameraPoseService.find(assetId)).thenReturn(Optional.of(pose(assetId, null, null)));
        MockMvc pilotMvc =
                mockMvcFor(currentUserWithScope(VisibilityScope.assignedAssets(Set.of(assetId))), enabledProperties());

        pilotMvc.perform(get("/api/assets/{assetId}/camera-pose", assetId.value()))
                .andExpect(status().isOk());
    }

    // ---- PUT: manageable, body-before-scope ordering ----

    @Test
    void putReturns200WithThePersistedPose() throws Exception {
        AssetId assetId = stubExistingAsset();
        when(cameraPoseService.put(eq(assetId), any(CameraPoseInput.class), eq(ownerId)))
                .thenReturn(pose(assetId, null, null));

        mockMvc.perform(put("/api/assets/{assetId}/camera-pose", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(putBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(assetId.value().toString()));

        verify(cameraPoseService).put(eq(assetId), any(CameraPoseInput.class), eq(ownerId));
    }

    @Test
    void putReturns400ForAMalformedBodyBeforeTheScopeGuardRuns() throws Exception {
        mockMvc.perform(put("/api/assets/{assetId}/camera-pose", AssetId.random().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"latitude":50.45,"longitude":30.52,"aglMeters":12.0,"yawDegrees":90.0,
                                "pitchDegrees":30.0,"hfovDegrees":60.0,"source":"NOT_A_SOURCE"}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(assetService);
    }

    @Test
    void putReturns404ForAnUnknownOrOutOfScopeAsset() throws Exception {
        AssetId assetId = AssetId.random();
        when(assetService.details(any(VisibilityScope.class), eq(assetId)))
                .thenThrow(new NoSuchElementException("Unknown asset"));

        mockMvc.perform(put("/api/assets/{assetId}/camera-pose", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(putBody()))
                .andExpect(status().isNotFound());
    }

    @Test
    void putReturns403WhenTheCallerMayNotManageTheAsset() throws Exception {
        AssetId assetId = stubExistingAsset();
        MockMvc pilotMvc =
                mockMvcFor(currentUserWithScope(VisibilityScope.assignedAssets(Set.of(assetId))), enabledProperties());

        pilotMvc.perform(put("/api/assets/{assetId}/camera-pose", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(putBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verify(cameraPoseService, never()).put(any(), any(), any());
    }

    // ---- DELETE ----

    @Test
    void deleteReturns204() throws Exception {
        AssetId assetId = stubExistingAsset();

        mockMvc.perform(delete("/api/assets/{assetId}/camera-pose", assetId.value()))
                .andExpect(status().isNoContent());

        verify(cameraPoseService).delete(assetId, ownerId);
    }

    @Test
    void deleteReturns403WhenTheCallerMayNotManageTheAsset() throws Exception {
        AssetId assetId = stubExistingAsset();
        MockMvc pilotMvc =
                mockMvcFor(currentUserWithScope(VisibilityScope.assignedAssets(Set.of(assetId))), enabledProperties());

        pilotMvc.perform(delete("/api/assets/{assetId}/camera-pose", assetId.value()))
                .andExpect(status().isForbidden());

        verify(cameraPoseService, never()).delete(any(), any());
    }

    // ---- POST calibration: never persists (D5) ----

    private static String calibrationBody() {
        return """
                {"latitude":50.45,"longitude":30.52,"aglMeters":12.0,"imageWidth":1920,"imageHeight":1080,
                "points":[
                  {"u":0.2,"v":0.6,"latitude":50.451,"longitude":30.521},
                  {"u":0.8,"v":0.6,"latitude":50.452,"longitude":30.523},
                  {"u":0.5,"v":0.7,"latitude":50.453,"longitude":30.519}
                ]}
                """;
    }

    @Test
    void calibrateNeverCallsPutOrDeleteRegardlessOfWhetherTheSolveSucceeds() throws Exception {
        AssetId assetId = stubExistingAsset();

        mockMvc.perform(post("/api/assets/{assetId}/camera-pose/calibration", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(calibrationBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.solved").exists());

        verify(cameraPoseService, never()).put(any(), any(), any());
        verify(cameraPoseService, never()).delete(any(), any());
    }

    @Test
    void calibrateReturns403WhenTheCallerMayNotManageTheAsset() throws Exception {
        AssetId assetId = stubExistingAsset();
        MockMvc pilotMvc =
                mockMvcFor(currentUserWithScope(VisibilityScope.assignedAssets(Set.of(assetId))), enabledProperties());

        pilotMvc.perform(post("/api/assets/{assetId}/camera-pose/calibration", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(calibrationBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void calibrateReturns400ForFewerThanTwoPoints() throws Exception {
        mockMvc.perform(post("/api/assets/{assetId}/camera-pose/calibration", AssetId.random().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"latitude":50.45,"longitude":30.52,"aglMeters":12.0,"imageWidth":1920,
                                "imageHeight":1080,"points":[{"u":0.5,"v":0.5,"latitude":50.45,"longitude":30.52}]}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(assetService);
    }

    @Test
    void calibrateReturns400ForANormalizedCoordinateOutsideZeroToOne() throws Exception {
        mockMvc.perform(post("/api/assets/{assetId}/camera-pose/calibration", AssetId.random().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"latitude":50.45,"longitude":30.52,"aglMeters":12.0,"imageWidth":1920,
                                "imageHeight":1080,"points":[
                                  {"u":1.5,"v":0.5,"latitude":50.451,"longitude":30.521},
                                  {"u":0.5,"v":0.5,"latitude":50.452,"longitude":30.523}
                                ]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void calibrateReturns404ForAnUnknownOrOutOfScopeAsset() throws Exception {
        AssetId assetId = AssetId.random();
        when(assetService.details(any(VisibilityScope.class), eq(assetId)))
                .thenThrow(new NoSuchElementException("Unknown asset"));

        mockMvc.perform(post("/api/assets/{assetId}/camera-pose/calibration", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(calibrationBody()))
                .andExpect(status().isNotFound());
    }

    @Test
    void malformedAssetIdReturns400OnEveryEndpoint() throws Exception {
        mockMvc.perform(get("/api/assets/not-a-uuid/camera-pose")).andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/assets/not-a-uuid/camera-pose")).andExpect(status().isBadRequest());
    }
}

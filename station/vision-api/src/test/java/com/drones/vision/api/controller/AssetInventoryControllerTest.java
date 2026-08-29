package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.warehouse.application.asset.AssetDetails;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.asset.AssetStatus;
import com.drones.vision.warehouse.application.asset.AssetSummary;
import com.drones.vision.warehouse.application.custody.AssetCustodyService;
import com.drones.vision.warehouse.application.maintenance.MaintenanceService;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.Identity;
import com.drones.vision.warehouse.domain.model.MaintenanceId;
import com.drones.vision.warehouse.domain.model.MaintenanceKind;
import com.drones.vision.warehouse.domain.model.MaintenanceRecord;
import com.drones.vision.warehouse.domain.port.AssetImageRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssetInventoryControllerTest {

    private static final CategoryId DRONE = new CategoryId("drone");

    private AssetCustodyService assetCustodyService;
    private MaintenanceService maintenanceService;
    private AssetService assetService;
    private AssetImageRepositoryPort assetImageRepositoryPort;
    private CurrentUser currentUser;
    private MockMvc mockMvc;
    private Asset asset;

    @BeforeEach
    void setUp() {
        assetCustodyService = mock(AssetCustodyService.class);
        maintenanceService = mock(MaintenanceService.class);
        assetService = mock(AssetService.class);
        assetImageRepositoryPort = mock(AssetImageRepositoryPort.class);
        currentUser = new CurrentUser(new Ownership(UserId.random(), GroupId.random()));
        mockMvc = MockMvcBuilders.standaloneSetup(new AssetInventoryController(assetCustodyService,
                        maintenanceService, assetService, assetImageRepositoryPort, currentUser))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        asset = Asset.register(AssetId.random(), "Drone 1", DRONE, new Ownership(UserId.random(), GroupId.random()),
                Set.of(DeviceId.random()), java.util.Map.of(), Identity.NONE, Custody.NONE);
        when(assetService.details(currentUser.scope(), asset.id())).thenReturn(detailsOf(asset));
        when(assetImageRepositoryPort.existsByAssetId(asset.id())).thenReturn(false);
    }

    private static AssetDetails detailsOf(Asset asset) {
        AssetSummary summary = new AssetSummary(asset, "Drone", AssetStatus.OFFLINE, null, null,
                asset.inventoryState(), asset.identity(), asset.custody());
        return new AssetDetails(summary, List.of(), List.of());
    }

    @Test
    void custodyIssueCallsTheServiceAndReturnsAssetDetails() throws Exception {
        UserId custodian = UserId.random();
        when(assetCustodyService.issue(asset.id(), custodian, "Van 3", currentUser.userId(), currentUser.scope()))
                .thenReturn(asset);

        mockMvc.perform(post("/api/assets/{id}/custody", asset.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"issue\",\"custodianId\":\"" + custodian.value()
                                + "\",\"location\":\"Van 3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(asset.id().value().toString()));
    }

    @Test
    void custodyReturnCallsTheService() throws Exception {
        when(assetCustodyService.returnToStock(asset.id(), currentUser.userId(), currentUser.scope()))
                .thenReturn(asset);

        mockMvc.perform(post("/api/assets/{id}/custody", asset.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"return\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void custodyRejectsAnUnknownAction() throws Exception {
        mockMvc.perform(post("/api/assets/{id}/custody", asset.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"loan\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void inventoryGroundOpensAMaintenanceRecordAndReturnsAssetDetails() throws Exception {
        when(assetCustodyService.ground(asset.id(), MaintenanceKind.GROUNDING, "Propeller crack",
                currentUser.userId(), currentUser.scope())).thenReturn(asset);

        mockMvc.perform(post("/api/assets/{id}/inventory", asset.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"ground\",\"kind\":\"grounding\",\"summary\":\"Propeller crack\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(asset.id().value().toString()));
    }

    @Test
    void inventoryReleaseCallsTheService() throws Exception {
        when(assetCustodyService.release(asset.id(), currentUser.userId(), currentUser.scope())).thenReturn(asset);

        mockMvc.perform(post("/api/assets/{id}/inventory", asset.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"release\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void inventoryRetireCallsTheService() throws Exception {
        when(assetCustodyService.retire(asset.id(), currentUser.userId(), currentUser.scope())).thenReturn(asset);

        mockMvc.perform(post("/api/assets/{id}/inventory", asset.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"retire\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void listMaintenanceReturns200WithTheAssetsHistory() throws Exception {
        MaintenanceRecord record = new MaintenanceRecord(MaintenanceId.random(), asset.id(),
                MaintenanceKind.INSPECTION_DUE, Instant.now(), null, currentUser.userId(), "100-hour check", null);
        when(maintenanceService.listForAsset(asset.id(), currentUser.scope())).thenReturn(List.of(record));

        mockMvc.perform(get("/api/assets/{id}/maintenance", asset.id().value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].kind").value("INSPECTION_DUE"))
                .andExpect(jsonPath("$[0].closedAt").doesNotExist());
    }

    @Test
    void openMaintenanceReturns201WithTheOpenedRecord() throws Exception {
        MaintenanceRecord opened = new MaintenanceRecord(MaintenanceId.random(), asset.id(), MaintenanceKind.NOTE,
                Instant.now(), null, currentUser.userId(), "Handover note", null);
        when(maintenanceService.open(asset.id(), MaintenanceKind.NOTE, "Handover note", currentUser.userId(),
                currentUser.scope())).thenReturn(opened);

        mockMvc.perform(post("/api/assets/{id}/maintenance", asset.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"note\",\"summary\":\"Handover note\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(opened.id().value().toString()));
    }

    @Test
    void closeMaintenanceReturns200WithTheClosedRecord() throws Exception {
        MaintenanceId recordId = MaintenanceId.random();
        Instant openedAt = Instant.now().minusSeconds(3600);
        MaintenanceRecord closed = new MaintenanceRecord(recordId, asset.id(), MaintenanceKind.REPAIR, openedAt,
                Instant.now(), currentUser.userId(), "Swapped motor", null);
        when(maintenanceService.close(recordId, currentUser.userId(), currentUser.scope())).thenReturn(closed);

        mockMvc.perform(post("/api/assets/{id}/maintenance/{recordId}/close", asset.id().value(), recordId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closedAt").exists());
    }

    @Test
    void custodyPropagatesAccessDeniedAsForbidden() throws Exception {
        when(assetCustodyService.returnToStock(asset.id(), currentUser.userId(), currentUser.scope()))
                .thenThrow(new AccessDeniedException("DENIED:out of scope"));

        mockMvc.perform(post("/api/assets/{id}/custody", asset.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"return\"}"))
                .andExpect(status().isForbidden());
    }
}

package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.identity.application.handover.HandoverService;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.Authority;
import com.drones.vision.warehouse.application.asset.AssetDetails;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.asset.AssetStatus;
import com.drones.vision.warehouse.application.asset.AssetSummary;
import com.drones.vision.warehouse.application.custody.AssetCustodyService;
import com.drones.vision.warehouse.application.maintenance.MaintenanceListState;
import com.drones.vision.warehouse.application.maintenance.MaintenanceRecordSummary;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssetInventoryControllerTest {

    private static final CategoryId DRONE = new CategoryId("drone");

    private HandoverService handoverService;
    private AssetCustodyService assetCustodyService;
    private MaintenanceService maintenanceService;
    private AssetService assetService;
    private AssetImageRepositoryPort assetImageRepositoryPort;
    private CurrentUser currentUser;
    private MockMvc mockMvc;
    private Asset asset;

    @BeforeEach
    void setUp() {
        handoverService = mock(HandoverService.class);
        assetCustodyService = mock(AssetCustodyService.class);
        maintenanceService = mock(MaintenanceService.class);
        assetService = mock(AssetService.class);
        assetImageRepositoryPort = mock(AssetImageRepositoryPort.class);
        currentUser = new CurrentUser(new Ownership(UserId.random(), GroupId.random()));
        mockMvc = MockMvcBuilders.standaloneSetup(new AssetInventoryController(handoverService, assetCustodyService,
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
    void custodyIssueGoesThroughHandoverSoTheCustodianAlsoGetsTheSeat() throws Exception {
        UserId custodian = UserId.random();
        when(handoverService.issue(asset.id(), custodian, "Van 3", currentUser.userId(), currentUser.authority()))
                .thenReturn(asset);

        mockMvc.perform(post("/api/assets/{id}/custody", asset.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"issue\",\"custodianId\":\"" + custodian.value()
                                + "\",\"location\":\"Van 3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(asset.id().value().toString()));

        verify(handoverService).issue(asset.id(), custodian, "Van 3", currentUser.userId(), currentUser.authority());
        verifyNoInteractions(assetCustodyService);
    }

    /**
     * A failed hand-over surfaces as the failure, not as a half-done issue: {@link HandoverService}
     * has already undone the custody write by the time the exception reaches here, and {@link
     * ApiExceptionHandler} maps it (409 for {@link IllegalStateException}) exactly as a failed plain
     * custody write always did.
     */
    @Test
    void custodyIssueSurfacesAHandoverFailureRatherThanAHalfState() throws Exception {
        UserId custodian = UserId.random();
        when(handoverService.issue(asset.id(), custodian, null, currentUser.userId(), currentUser.authority()))
                .thenThrow(new IllegalStateException("assignment write failed"));

        mockMvc.perform(post("/api/assets/{id}/custody", asset.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"issue\",\"custodianId\":\"" + custodian.value() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void custodyReturnGoesThroughHandoverAndKeepsTheAssignment() throws Exception {
        when(handoverService.returnToStock(asset.id(), currentUser.userId(), currentUser.authority()))
                .thenReturn(asset);

        mockMvc.perform(post("/api/assets/{id}/custody", asset.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"return\"}"))
                .andExpect(status().isOk());

        verify(handoverService).returnToStock(asset.id(), currentUser.userId(), currentUser.authority());
        verifyNoInteractions(assetCustodyService);
    }

    @Test
    void groundingStillGoesStraightToCustodyNotHandover() throws Exception {
        when(assetCustodyService.ground(asset.id(), MaintenanceKind.GROUNDING, "Bent arm",
                currentUser.userId(), currentUser.authority())).thenReturn(asset);

        mockMvc.perform(post("/api/assets/{id}/inventory", asset.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"ground\",\"kind\":\"grounding\",\"summary\":\"Bent arm\"}"))
                .andExpect(status().isOk());

        verifyNoInteractions(handoverService);
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
                currentUser.userId(), currentUser.authority())).thenReturn(asset);

        mockMvc.perform(post("/api/assets/{id}/inventory", asset.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"ground\",\"kind\":\"grounding\",\"summary\":\"Propeller crack\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(asset.id().value().toString()));
    }

    @Test
    void inventoryReleaseCallsTheService() throws Exception {
        when(assetCustodyService.release(asset.id(), currentUser.userId(), currentUser.authority())).thenReturn(asset);

        mockMvc.perform(post("/api/assets/{id}/inventory", asset.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"release\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void inventoryRetireCallsTheService() throws Exception {
        when(assetCustodyService.retire(asset.id(), currentUser.userId(), currentUser.authority())).thenReturn(asset);

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
                currentUser.authority())).thenReturn(opened);

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
        when(maintenanceService.close(recordId, currentUser.userId(), currentUser.authority())).thenReturn(closed);

        mockMvc.perform(post("/api/assets/{id}/maintenance/{recordId}/close", asset.id().value(), recordId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closedAt").exists());
    }

    @Test
    void custodyPropagatesAccessDeniedAsForbidden() throws Exception {
        when(handoverService.returnToStock(asset.id(), currentUser.userId(), currentUser.authority()))
                .thenThrow(new AccessDeniedException("DENIED:out of scope"));

        mockMvc.perform(post("/api/assets/{id}/custody", asset.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"return\"}"))
                .andExpect(status().isForbidden());
    }

    // ---- GET /api/maintenance ----

    @Test
    void fleetMaintenanceDefaultsToOpenAndTheDefaultLimitAndJoinsTheAssetNameAndCategory() throws Exception {
        MaintenanceRecord record = new MaintenanceRecord(MaintenanceId.random(), asset.id(),
                MaintenanceKind.GROUNDING, Instant.now(), null, currentUser.userId(), "prop strike", null);
        MaintenanceRecordSummary summary = new MaintenanceRecordSummary(record, "Drone 1", DRONE);
        when(maintenanceService.fleetWide(MaintenanceListState.OPEN, 200, currentUser.scope()))
                .thenReturn(List.of(summary));

        mockMvc.perform(get("/api/maintenance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].assetId").value(asset.id().value().toString()))
                .andExpect(jsonPath("$[0].assetName").value("Drone 1"))
                .andExpect(jsonPath("$[0].categoryId").value("drone"))
                .andExpect(jsonPath("$[0].kind").value("GROUNDING"));
    }

    @Test
    void fleetMaintenancePassesStateAndLimitThrough() throws Exception {
        when(maintenanceService.fleetWide(MaintenanceListState.CLOSED, 5, currentUser.scope()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/maintenance").param("state", "closed").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        verify(maintenanceService).fleetWide(MaintenanceListState.CLOSED, 5, currentUser.scope());
    }

    @Test
    void fleetMaintenanceAcceptsAllCaseInsensitively() throws Exception {
        when(maintenanceService.fleetWide(MaintenanceListState.ALL, 200, currentUser.scope())).thenReturn(List.of());

        mockMvc.perform(get("/api/maintenance").param("state", "ALL"))
                .andExpect(status().isOk());

        verify(maintenanceService).fleetWide(MaintenanceListState.ALL, 200, currentUser.scope());
    }

    @Test
    void fleetMaintenanceReturns400ForAnUnknownState() throws Exception {
        mockMvc.perform(get("/api/maintenance").param("state", "expired"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }
}

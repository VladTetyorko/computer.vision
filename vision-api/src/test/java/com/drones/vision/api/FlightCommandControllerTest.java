package com.drones.vision.api;

import com.drones.vision.api.exceptions.ApiExceptionHandler;
import com.drones.vision.application.AccessDeniedException;
import com.drones.vision.application.FlightCommandService;
import com.drones.vision.application.VisibilityScope;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.CommandResult;
import com.drones.vision.domain.model.FlightCapability;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FlightCommandControllerTest {

    private FlightCommandService flightCommandService;
    private MockMvc mockMvc;

    private final UserId ownerId = UserId.random();
    private final Ownership ownership = new Ownership(ownerId, GroupId.random());
    private final CurrentUser currentUser = new CurrentUser(ownership);

    @BeforeEach
    void setUp() {
        flightCommandService = mock(FlightCommandService.class);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new FlightCommandController(flightCommandService, currentUser))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void returnHomeReturns202WithAcceptedOnSuccess() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.returnToHome(eq(assetId), eq(ownerId), any(VisibilityScope.class))).thenReturn(CommandResult.ACCEPTED);

        mockMvc.perform(post("/api/assets/{id}/return-home", assetId.value()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.result").value("ACCEPTED"));

        verify(flightCommandService).returnToHome(eq(assetId), eq(ownerId), any(VisibilityScope.class));
    }

    @Test
    void returnHomeReturns202WithNoAckWhenTheAircraftNeverAcknowledged() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.returnToHome(eq(assetId), eq(ownerId), any(VisibilityScope.class))).thenReturn(CommandResult.NO_ACK);

        mockMvc.perform(post("/api/assets/{id}/return-home", assetId.value()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.result").value("NO_ACK"));
    }

    @Test
    void returnHomeReturns404ForAnUnknownAsset() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.returnToHome(eq(assetId), eq(ownerId), any(VisibilityScope.class)))
                .thenThrow(new NoSuchElementException("Unknown asset: " + assetId.value()));

        mockMvc.perform(post("/api/assets/{id}/return-home", assetId.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void returnHomeReturns400ForABadUuidAndNeverTouchesTheService() throws Exception {
        mockMvc.perform(post("/api/assets/{id}/return-home", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void returnHomeReturns409WhenTheAssetHasNoCommandableDevice() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.returnToHome(eq(assetId), eq(ownerId), any(VisibilityScope.class)))
                .thenThrow(new IllegalStateException("Asset " + assetId.value() + " has no active MAVLink "
                        + "telemetry device to command"));

        mockMvc.perform(post("/api/assets/{id}/return-home", assetId.value()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Asset " + assetId.value()
                        + " has no active MAVLink telemetry device to command"));
    }

    @Test
    void returnHomeReturns403WhenTheAssetIsOutOfScope() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.returnToHome(eq(assetId), eq(ownerId), any(VisibilityScope.class)))
                .thenThrow(new AccessDeniedException("Asset " + assetId.value() + " is outside your scope"));

        mockMvc.perform(post("/api/assets/{id}/return-home", assetId.value()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void returnHomeReturns409WhenTheAircraftRefusesTheCommand() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.returnToHome(eq(assetId), eq(ownerId), any(VisibilityScope.class)))
                .thenThrow(new IllegalStateException("Vehicle sysid 1 refused return-to-home: MAV_RESULT_DENIED"));

        mockMvc.perform(post("/api/assets/{id}/return-home", assetId.value()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Vehicle sysid 1 refused return-to-home: MAV_RESULT_DENIED"));
    }

    // --- Stage 2: mode ---

    @Test
    void setModeReturns202WithResult() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.setMode(eq(assetId), eq("Loiter"), eq(ownerId), any(VisibilityScope.class)))
                .thenReturn(CommandResult.ACCEPTED);

        mockMvc.perform(post("/api/assets/{id}/mode", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"Loiter\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.result").value("ACCEPTED"));

        verify(flightCommandService).setMode(eq(assetId), eq("Loiter"), eq(ownerId), any(VisibilityScope.class));
    }

    @Test
    void setModeReturns400ForABlankMode() throws Exception {
        AssetId assetId = AssetId.random();

        mockMvc.perform(post("/api/assets/{id}/mode", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void setModeReturns400ForAnUnknownMode() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.setMode(eq(assetId), eq("Barrel-Roll"), eq(ownerId), any(VisibilityScope.class)))
                .thenThrow(new IllegalArgumentException("Unknown flight mode 'Barrel-Roll'"));

        mockMvc.perform(post("/api/assets/{id}/mode", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"Barrel-Roll\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void setModeReturns409WhenNotCommandable() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.setMode(eq(assetId), eq("Loiter"), eq(ownerId), any(VisibilityScope.class)))
                .thenThrow(new IllegalStateException("Asset has no active MAVLink telemetry device to command"));

        mockMvc.perform(post("/api/assets/{id}/mode", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"Loiter\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    // --- Stage 2: arm / disarm ---

    @Test
    void armReturns202AndForcedFlagPassedThrough() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.arm(eq(assetId), eq(true), eq(ownerId), any(VisibilityScope.class)))
                .thenReturn(CommandResult.ACCEPTED);

        mockMvc.perform(post("/api/assets/{id}/arm", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"force\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.result").value("ACCEPTED"));

        verify(flightCommandService).arm(eq(assetId), eq(true), eq(ownerId), any(VisibilityScope.class));
    }

    @Test
    void armDefaultsForceToFalseWhenBodyAbsent() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.arm(eq(assetId), eq(false), eq(ownerId), any(VisibilityScope.class)))
                .thenReturn(CommandResult.NO_ACK);

        mockMvc.perform(post("/api/assets/{id}/arm", assetId.value()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.result").value("NO_ACK"));

        verify(flightCommandService).arm(eq(assetId), eq(false), eq(ownerId), any(VisibilityScope.class));
    }

    @Test
    void armReturns403WhenOutOfScope() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.arm(eq(assetId), anyBoolean(), eq(ownerId), any(VisibilityScope.class)))
                .thenThrow(new AccessDeniedException("Asset " + assetId.value() + " is outside your scope"));

        mockMvc.perform(post("/api/assets/{id}/arm", assetId.value()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void disarmReturns202AndDefaultsForceToFalse() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.disarm(eq(assetId), eq(false), eq(ownerId), any(VisibilityScope.class)))
                .thenReturn(CommandResult.ACCEPTED);

        mockMvc.perform(post("/api/assets/{id}/disarm", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.result").value("ACCEPTED"));

        verify(flightCommandService).disarm(eq(assetId), eq(false), eq(ownerId), any(VisibilityScope.class));
    }

    @Test
    void disarmReturns409WhenAircraftRefuses() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.disarm(eq(assetId), anyBoolean(), eq(ownerId), any(VisibilityScope.class)))
                .thenThrow(new IllegalStateException("Vehicle refused disarm: MAV_RESULT_DENIED"));

        mockMvc.perform(post("/api/assets/{id}/disarm", assetId.value()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Vehicle refused disarm: MAV_RESULT_DENIED"));
    }

    // --- Stage 2: flight-capabilities (read) ---

    @Test
    void flightCapabilitiesReturns200WithTheSnapshotShape() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.capabilities(eq(assetId), any(VisibilityScope.class)))
                .thenReturn(new FlightCapability(true, true, true, List.of("Loiter", "RTL")));

        mockMvc.perform(get("/api/assets/{id}/flight-capabilities", assetId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commandable").value(true))
                .andExpect(jsonPath("$.armSupported").value(true))
                .andExpect(jsonPath("$.modeSelectSupported").value(true))
                .andExpect(jsonPath("$.selectableModes[0]").value("Loiter"))
                .andExpect(jsonPath("$.selectableModes[1]").value("RTL"));
    }

    @Test
    void flightCapabilitiesReturns404WhenTheScopedReadHidesTheAsset() throws Exception {
        AssetId assetId = AssetId.random();
        when(flightCommandService.capabilities(eq(assetId), any(VisibilityScope.class)))
                .thenThrow(new NoSuchElementException("Unknown asset: " + assetId.value()));

        mockMvc.perform(get("/api/assets/{id}/flight-capabilities", assetId.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void flightCapabilitiesReturns400ForABadUuid() throws Exception {
        mockMvc.perform(get("/api/assets/{id}/flight-capabilities", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }
}
